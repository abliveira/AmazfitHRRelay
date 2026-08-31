# Amazfit HR Relay

Amazfit HR Relay is an Android application designed for compatible Amazfit watches that do not provide a reliable native way to share heart rate data over Bluetooth.

It reads live heart rate data from the watch and exposes it as a standard Bluetooth Heart Rate Monitor, making it useful with applications such as MyWhoosh.

```text
┌─────────────────────────┐
│      AMAZFIT WATCH      │
│                         │
│    Live Heart Rate      │
└────────────┬────────────┘
             │
             │ BLE
             ▼
┌─────────────────────────┐
│      ANDROID PHONE      │
│                         │
│     Amazfit HR Relay    │
└────────────┬────────────┘
             │
             │ BLE
             ▼
┌─────────────────────────┐
│   FITNESS APPLICATION   │
│                         │
│     MyWhoosh, etc.      │
└─────────────────────────┘
```

## Compatibility

Amazfit HR Relay was tested with the Amazfit GTR 2.

It may also work with other compatible Amazfit watches that expose heart rate data through the standard Bluetooth Heart Rate Service (**0x180D / 0x2A37**) but do not offer a suitable native heart rate sharing feature.

## Environment Setup

Before building the project, configure the watch connection details.

### Requirements

You will need:

* Android Studio
* An Android device with Bluetooth Low Energy support
* A compatible Amazfit watch
* The watch MAC address
* The watch authentication key

#### Authentication Key

The authentication key can be obtained using the Notify for Amazfit application and compatible key extraction methods.

The key must be provided as a 32-character hexadecimal value:

```text
XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
```
### local.properties

Add the watch configuration to your project's `local.properties` file:

```properties
AMAZFIT_MAC=XX:XX:XX:XX:XX:XX
AUTH_KEY_HEX=XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
```

## Usage

### 1. Battery Optimization

For reliable operation during longer activities, disable battery restrictions for Amazfit HR Relay.

Inside the application, tap the battery warning card to open the Android battery optimization settings.

Select:

```text
Unrestricted
```

### 2. Start a Workout

Start an activity on the watch that forces continuous heart rate monitoring, for example Indoor Cycling.

### 3. Start the Relay

Open Amazfit HR Relay and tap:

```text
START RELAY
```

The application will:

1. Connect to the configured watch.
2. Authenticate with the watch.
3. Enable live heart rate notifications.
4. Advertise a standard Bluetooth Heart Rate service.

### 4. Connect Your Application

The relay will appear using your phone's Bluetooth device name as a standard Heart Rate service.  
Select your phone from the available heart rate sensors to connect.

## Disclaimer

Amazfit, Zepp, Xiaomi, and MyWhoosh are trademarks of their respective owners. This project is not affiliated with or endorsed by any of them.
