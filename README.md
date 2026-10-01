# Order Taking

Android app for counter ordering with a kitchen display. One APK, two modes:

- **Cashier** — visual menu grid, tap to add to the order tray, pick options (cook temperature, extras), add notes, enter a table number or customer name, then **Send to Kitchen**.
- **Kitchen display** — incoming orders appear instantly as tickets (oldest on the left) with a timer, a sound alert, **START** (turns the ticket amber) and **DONE** (removes it and tells the cashier the order is ready). **Recall last** brings back a ticket bumped by mistake.

There is no payment step, no cloud service and no account to create.

## How it connects

The kitchen tablet runs a small WebSocket server (port 8765) on the restaurant Wi-Fi and announces itself with mDNS, so cashier tablets find it automatically. If a network blocks that, type the IP shown at the top of the kitchen screen into the cashier's **Settings → Kitchen IP address**.

Every order is saved on the cashier tablet until the kitchen confirms it, so orders taken while the Wi-Fi drops (or the kitchen tablet restarts) are delivered automatically when it comes back. Kitchen tickets are also saved, so restarting the kitchen tablet doesn't lose them.

Order payload (`SUBMIT_ORDER` message):

```json
{
  "order_id": "ORD-3F9A1C22B0",
  "timestamp": "2026-10-01T08:40:00Z",
  "origin": "Table 4",
  "items": [
    { "item_id": "101", "name": "Classic Cheeseburger", "quantity": 2, "modifiers": ["Medium rare"], "notes": "No onions" }
  ],
  "status": "pending",
  "taken_by": "Sam"
}
```

## Setup in the restaurant

1. Install the APK on every tablet (allow "Install unknown apps" for your browser / file manager).
2. Put all tablets on the same Wi-Fi.
3. Open the app on the kitchen tablet → **Kitchen display**. On each counter tablet → **Cashier**.
4. On a cashier tablet, open ⚙ Settings to edit the menu (photos, prices, categories, option groups, sold-out switch) and set the cashier name and currency.

Tips: keep the kitchen tablet plugged in (the app keeps the screen on), and give it a fixed IP (DHCP reservation in your router) if you use the manual address.

## Building

Requires JDK 17 and the Android SDK (platform 36).

```bash
./gradlew testDebugUnitTest assembleRelease
```

Release signing reads `keystore.properties` (not committed); without it the release build is debug-signed. Keep the keystore safe — updates must be signed with the same key to install over an existing copy.
