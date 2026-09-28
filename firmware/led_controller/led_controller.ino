#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLESecurity.h>
#include <esp_gap_ble_api.h>

// Tested API target: Espressif Arduino-ESP32 Core 3.0.7.
// Replace this value with a unique code before deploying a board.
static const uint32_t DEVICE_PASSKEY = 482731;

static const char *DEVICE_NAME = "LEDCTRL-01";
static const char *SERVICE_UUID = "7e57d004-2b97-4db4-b160-5d8f9d7a1000";
static const char *INFO_UUID = "7e57d004-2b97-4db4-b160-5d8f9d7a1001";
static const char *CONTROL_UUID = "7e57d004-2b97-4db4-b160-5d8f9d7a1002";
static const char *STATE_UUID = "7e57d004-2b97-4db4-b160-5d8f9d7a1003";

// Defaults for classic ESP32 WROOM/WROVER. Verify pin availability on C3/S3.
static const uint8_t LED_PINS[4] = {16, 17, 18, 19};
static const uint8_t FACTORY_RESET_PIN = 0; // Hold BOOT while powering on.
static const uint32_t FACTORY_RESET_HOLD_MS = 5000;

static BLEServer *server = nullptr;
static BLECharacteristic *stateCharacteristic = nullptr;
static BLE2902 *stateCccd = nullptr;
static bool clientConnected = false;

static uint8_t readOutputMask() {
  uint8_t mask = 0;
  for (uint8_t i = 0; i < 4; ++i) {
    if (digitalRead(LED_PINS[i]) == HIGH) mask |= (1U << i);
  }
  return mask;
}

static void applyOutputMask(uint8_t requested) {
  const uint8_t mask = requested & 0x0F;
  for (uint8_t i = 0; i < 4; ++i) {
    digitalWrite(LED_PINS[i], (mask & (1U << i)) ? HIGH : LOW);
  }
}

static void publishState() {
  const uint8_t actual = readOutputMask();
  stateCharacteristic->setValue(&actual, 1);
  if (clientConnected && stateCccd != nullptr && stateCccd->getNotifications()) {
    stateCharacteristic->notify();
  }
  Serial.printf("GPIO state changed: 0x%02X\n", actual);
}

class ControlCallbacks final : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    const String value = characteristic->getValue();
    if (value.length() != 1) {
      Serial.println("Rejected control payload with invalid length");
      return;
    }
    applyOutputMask(static_cast<uint8_t>(value[0]));
    publishState();
  }
};

class ServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer *) override {
    clientConnected = true;
    Serial.println("BLE client connected");
  }

  void onDisconnect(BLEServer *) override {
    clientConnected = false;
    applyOutputMask(0); // Fail-safe: outputs are off without an authenticated controller.
    publishState();
    Serial.println("BLE client disconnected; outputs switched off");
    BLEDevice::startAdvertising();
  }
};

static void configureSecurity() {
  BLESecurity *security = new BLESecurity();
  security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_MITM_BOND);
  security->setCapability(ESP_IO_CAP_OUT);
  security->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  security->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  security->setStaticPIN(DEVICE_PASSKEY);
}

static void performFactoryResetIfRequested() {
  pinMode(FACTORY_RESET_PIN, INPUT_PULLUP);
  if (digitalRead(FACTORY_RESET_PIN) != LOW) return;

  const uint32_t started = millis();
  while (digitalRead(FACTORY_RESET_PIN) == LOW && millis() - started < FACTORY_RESET_HOLD_MS) {
    delay(20);
  }
  if (millis() - started >= FACTORY_RESET_HOLD_MS) {
    Serial.println("Factory reset: deleting all BLE bonds");
    int count = esp_ble_get_bond_device_num();
    if (count > 0) {
      esp_ble_bond_dev_t *devices = static_cast<esp_ble_bond_dev_t *>(
          malloc(sizeof(esp_ble_bond_dev_t) * count));
      if (devices != nullptr && esp_ble_get_bond_device_list(&count, devices) == ESP_OK) {
        for (int i = 0; i < count; ++i) {
          esp_ble_remove_bond_device(devices[i].bd_addr);
        }
      }
      free(devices);
    }
  }
}

void setup() {
  Serial.begin(115200);
  for (uint8_t pin : LED_PINS) {
    pinMode(pin, OUTPUT);
    digitalWrite(pin, LOW);
  }

  BLEDevice::init(DEVICE_NAME);
  performFactoryResetIfRequested();
  configureSecurity();

  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService *service = server->createService(SERVICE_UUID);

  BLECharacteristic *info = service->createCharacteristic(
      INFO_UUID, BLECharacteristic::PROPERTY_READ);
  const uint8_t protocolInfo[2] = {1, 0x0F};
  info->setValue(protocolInfo, sizeof(protocolInfo));
  info->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM);

  BLECharacteristic *control = service->createCharacteristic(
      CONTROL_UUID, BLECharacteristic::PROPERTY_WRITE);
  control->setCallbacks(new ControlCallbacks());
  control->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);

  stateCharacteristic = service->createCharacteristic(
      STATE_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  stateCharacteristic->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM);
  stateCccd = new BLE2902();
  stateCharacteristic->addDescriptor(stateCccd);
  publishState();

  service->start();
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMaxPreferred(0x12);
  BLEDevice::startAdvertising();
  Serial.println("LEDCTRL is ready for secure pairing");
}

void loop() {
  delay(50);
}
