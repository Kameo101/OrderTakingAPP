# Order Taking

Android app for counter ordering with a kitchen display. One APK, two modes:

- **Cashier** — visual menu grid, tap to add to the order tray, pick options (cook temperature, extras), add notes, type the customer's name, then **Send to Kitchen**.
- **Kitchen display** — incoming orders appear instantly as tickets (oldest on the left) with a timer, a bell chime (on the media volume; the screen warns if the volume is off), **START** (turns the ticket amber) and **DONE** (removes it and tells the cashiers the order is ready).
- **Who hands out orders** — by default the kitchen does, so DONE simply finishes the order. If the cashier hands orders out instead, turn on **Kitchen settings → Cashier hands out orders** (one switch on the kitchen tablet controls every cashier).
- **Ready for pickup** (cashier, only when that switch is on) — when the kitchen taps DONE, a green strip appears under the top bar with a card per order: the customer's name, the items and how long it has been waiting. Tap **✓ Handed out** once the customer has it (an **Undo** appears briefly). The strip is hidden when nothing is waiting, is shared by every cashier tablet, and survives the app closing or the Wi-Fi dropping. **Recall last** brings back a ticket bumped by mistake.

- **📊 Sales** (on both screens) — order history and a sales report for Today / Yesterday / Last 7 days / Last 30 days / All time: number of orders, items sold, sales total, average order, best sellers (how many of each item sold and for how much), most-picked options, a day-by-day table, and every order with its items. **Export to spreadsheet** saves a CSV you can open in Excel or Google Sheets. Press and hold an order to delete it (two confirmations) if it was entered by mistake; deleting only affects that tablet's history.

There is no payment step, no cloud service and no account to create.

## Order history storage

Every order is written to `order_history.jsonl` in the app's private storage the moment it is received, and synced to disk, so closing the app, restarting the tablet or a power cut doesn't lose it. History is never trimmed automatically.

- The **kitchen tablet** keeps every order from every cashier — use it for the full sales picture.
- Each **cashier tablet** keeps the orders it took (recorded once the kitchen confirms them).

The history is only deleted if the app is uninstalled or its data is cleared in Android settings, so export to a spreadsheet regularly as a backup.

## How it connects

The kitchen tablet runs a small WebSocket server (port 8765) on the restaurant Wi-Fi and announces itself with mDNS, so cashier tablets find it automatically. If a network blocks that, type the IP shown at the top of the kitchen screen into the cashier's **Settings → Kitchen IP address**.

Every order is saved on the cashier tablet until the kitchen confirms it, so orders taken while the Wi-Fi drops (or the kitchen tablet restarts) are delivered automatically when it comes back. Kitchen tickets are also saved, so restarting the kitchen tablet doesn't lose them.

Order payload (`SUBMIT_ORDER` message):

```json
{
  "order_id": "ORD-3F9A1C22B0",
  "timestamp": "2026-10-01T08:40:00Z",
  "origin": "Maria",
  "items": [
    { "item_id": "101", "name": "Classic Cheeseburger", "quantity": 2, "modifiers": ["Medium rare"], "notes": "No onions", "price": 12.50 }
  ],
  "status": "pending",
  "taken_by": "Sam"
}
```

## Setup in the restaurant

1. Install the APK on every tablet (allow "Install unknown apps" for your browser / file manager).
2. Put all tablets on the same Wi-Fi.
3. Open the app on the kitchen tablet → **Kitchen display**. On each counter tablet → **Cashier**.
4. On a cashier tablet, tap **Add your first menu item** (or ⚙ Settings → Add item) and build your menu: name, price, category, a photo (choose one or take one with the tablet camera), optional option groups, and a sold-out switch. Set the cashier name and currency there too.

### Using a phone hotspot (food truck / fair)

Turn on the hotspot, connect every tablet to it, and open the app. Cashiers find the kitchen by:
1. automatic discovery (mDNS),
2. the address the kitchen was last seen at,
3. if both fail, a quick scan of the hotspot network for the kitchen (about 2 seconds).

The app doesn't use the internet, so it works with no mobile data — the hotspot just has to be switched on. Use a phone as the hotspot and connect all the tablets to it.

### Using Bluetooth instead of Wi-Fi

If the Wi-Fi or hotspot is giving trouble, cashiers can connect to the kitchen over Bluetooth instead. Wi-Fi stays the default.

1. Pair each cashier tablet with the kitchen tablet in Android's **Settings → Bluetooth** (one time).
2. Kitchen: **Kitchen settings → Bluetooth connection** on (allow the "Nearby devices" permission). Wi-Fi cashiers keep working alongside Bluetooth ones.
3. Cashier: **Settings → Connect by Bluetooth** on, then tap the kitchen tablet in the list of paired devices.

Bluetooth reaches about 10 m and the kitchen takes a handful of Bluetooth cashiers at once. Unsent orders are kept and delivered the same way as over Wi-Fi. Turn the cashier switch off to go back to Wi-Fi.

Switching Bluetooth on (on either tablet) opens a step-by-step guide with pictures; **Show me how** reopens it. If a cashier on Wi-Fi can't reach the kitchen for 5 minutes, a tip suggests trying Bluetooth. It stays until **OK** is tapped, or closes by itself when the Wi-Fi comes back.

Tips: keep the kitchen tablet plugged in (the app keeps the screen on), and give it a fixed IP (DHCP reservation in your router) if you use the manual address.

## Building

Requires JDK 17 and the Android SDK (platform 36).

```bash
./gradlew testDebugUnitTest assembleRelease
```

Release signing reads `keystore.properties` (not committed); without it the release build is debug-signed. Keep the keystore safe — updates must be signed with the same key to install over an existing copy.
