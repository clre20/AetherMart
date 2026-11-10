# AetherMart

AetherMart 是一個 Paper 伺服器用的綜合商店插件：系統商店與玩家拍賣場放在同一套箱子介面裡，支援 Vault 預設幣與 ExcellentEconomy 特殊幣，並內建 Discord 機器人，把每一筆交易做成 PDF 明細私訊給玩家、每日彙整送到管理員頻道。

玩家只需要記住 `/mart`，其他所有操作都在介面裡完成；基岩版玩家（Geyser）一樣使用箱子介面。

## 功能特性

- **系統商店**
  - **分類與商品**：管理員在遊戲內點自己背包的物品就能上架，完整保留 NBT，背包裡的物品不會被拿走。
  - **多幣別定價**：每個商品可選預設幣或特殊幣定價。
  - **購買上限**：每個商品可設定每日／每週／累計／不限的每人上限，重置時間可設定。
  - **促銷排程**：在箱子介面新增促銷，開始時間預設「現在」，結束時間一鍵選 1 小時～30 天，也可輸入 `11/1`、`11/1 20:00` 或 `3d` 這類簡短格式；可打折（%）或直接設定促銷價，時段不可重疊。
  - **稅率與最低售價**：在 `shop.yml` 依幣別分開設定，稅由買家另付。
- **玩家拍賣**
  - **整包拍賣**：每筆拍賣是一個 27 格的小箱子空間，整包一起賣。
  - **一口價或競標**：賣家自選模式、幣別（管理員可限制）、價格與拍賣時間。
  - **出價不先扣款**：出價只檢查餘額（含稅），落槌成交時才扣款；每次至少加價 5%（可設定）。
  - **落槌制**：結束時間到後每 5 分鐘敲一槌，有人加價就重新起算，第 3 槌成交（皆可設定）。
  - **違約處理**：得標者付不出錢時，禁止在 AetherMart 買賣 N 天，並處以得標價 X% 的罰金（系統回收）；不夠扣的部分記為欠款，付清前不能交易（下次交易自動扣款）；拍賣改由上一位出價者以他自己的出價成交。
  - **領取箱**：買到、得標、退回的物品一律進領取箱，不會掉在地上；收入與退款直接存入對應貨幣帳戶，只有入帳失敗才放進領取箱。
  - **禁止販售**：管理員在介面中點物品加入，比對完整 NBT，連潛影盒、束口袋裡的物品也會檢查。
  - **休市與強制下架**：休市期間不能上架、購買或出價；強制下架時物品退回賣家，出價者都不會被扣款。
- **Discord 通知（內建機器人，填入 Token 即可）**
  - **個人私訊 PDF**：購買、得標、賣出、上架、下架、流標、被超過、違約都會產生一份 PDF 明細；玩家可在個人設定頁逐項開關（違約通知無法關閉）。
  - **管理員頻道**：每筆交易即時卡片（可關閉）＋每日結算時間送出每日彙整 PDF。
  - **尖峰合併與限速**：通知排隊慢慢送，佇列過長時同一人的通知合併成一則，避免卡住或被 Discord 限流。
  - **綁定檢查**：讀取 [DiscordLink](https://github.com/clre20/Discord-link-Minecraft-Plugin) 的綁定資料（唯讀），可設定未綁定時不能購買或上架。
- **PDF 明細**
  - 繁中、簡中、英文都能正確顯示（內建 Noto Sans TC／SC 字型）。
  - 有自訂名稱的物品顯示該名稱，其他物品顯示原版翻譯，並列出附魔與耐久。
  - 左下角依類型蓋上不同顏色的印章；所有文字、伺服器名稱、顏色、字型都在 `pdf.yml` 設定。
  - 每日彙整 PDF 本機保存 90 天（可設定），過期自動刪除。
- **全方位紀錄**：所有交易、管理操作、Discord 傳送結果都寫入 `logs/` 下的 .log，依天數或大小自動壓縮，並依保留天數與總大小清理。
- **多語言**：`config.yml` 的 `language` 可選 `zh_tw`、`zh_cn`、`en_us`；換語言後重載，舊的 `messages.yml`、`pdf.yml` 會自動備份並以新語言重建。
- **設定檔自動補齊**：啟動與重載時自動補上缺少的項目，保留你原本的值與註解；改版時，你沒改過的訊息會自動換成新版文字。
- **介面簽署**：所有介面都使用自訂 `InventoryHolder`（`getPlugin()`、`getMenuId()`）並在物品寫入 PDC `aethermart:gui`，方便其他插件（例如 Item-lock）辨識。
- **檢查更新**：啟動時非同步檢查 GitHub Releases，結果快取 10 分鐘，有權限的管理員登入 3 秒後收到提醒。

## 指令與權限

| 指令 | 說明 | 執行者 | 權限節點 | 預設 |
| --- | --- | --- | --- | --- |
| `/aethermart`（別名 `/mart`） | 打開分流介面（系統商店、玩家拍賣、個人設定） | 玩家 | `aethermart.use` | 所有人 |
| `/mart shop` | 直接打開系統商店 | 玩家 | `aethermart.use` | 所有人 |
| `/mart auction` | 直接打開玩家拍賣 | 玩家 | `aethermart.use` | 所有人 |
| `/mart help` | 顯示說明（只列出有權限的指令） | 玩家／後台 | `aethermart.help` | 所有人 |
| `/mart version` | 顯示版本並檢查更新 | 玩家／後台 | `aethermart.version` | OP |
| `/mart reload` | 重新載入並補齊所有設定檔 | 玩家／後台 | `aethermart.reload` | OP |
| `/mart admin` | 打開管理介面 | 玩家 | `aethermart.admin.use` | OP |
| `/mart admin auction` | 拍賣管理（強制下架、休市、禁止販售、受罰名單） | 玩家 | `aethermart.admin.auction` | OP |
| `/mart admin shop` | 商店管理（分類、上架、編輯、下架、促銷） | 玩家 | `aethermart.admin.shop` | OP |
| `/mart admin log [玩家]` | 交易紀錄 | 玩家 | `aethermart.admin.log` | OP |
| — | 建立拍賣 | 玩家 | `aethermart.auction.sell` | 所有人 |
| — | 同時上架數量加碼為 n 筆 | 玩家 | `aethermart.limit.<n>` | — |
| — | 全部權限 | — | `aethermart.*` | OP |

## 使用範例

### 1. 安裝

```bash
# 需求：Paper 1.21.x ～ 26.x、Java 21+、Vault 與一個經濟插件（缺少時後台會說明缺什麼）
# 選用：ExcellentEconomy（特殊幣）、DiscordLink（Discord 綁定）
plugins/
├── AetherMart-1.1.0.jar
├── Vault.jar
└── TheosisEconomy.jar
```

### 2. 設定特殊幣

```yaml
# config.yml
currencies:
  special:
    type: excellenteconomy
    id: "gems"        # ExcellentEconomy 裡的貨幣 ID
    name: "寶石"
    unit: "寶石"
```

### 3. 上架系統商店商品

```bash
/mart admin shop
# 新增分類 → 商品列表 → 點自己背包的物品 → 設定價格、幣別、上限 → 儲存
```

### 4. 啟用 Discord 通知

```yaml
# config.yml
discord:
  token: "你的機器人 Token"
  admin:
    channel-id: "123456789012345678"
    daily-summary:
      time: "00:00"
```

### 5. 玩家建立拍賣

```bash
/mart
# 玩家拍賣 → 建立拍賣 → 放入物品 → 選模式、幣別、價格、時間 → 確認上架
```

## 設定檔

| 檔案 | 說明 |
| --- | --- |
| `config.yml` | 語言、時區、幣別、聊天輸入、Discord、紀錄、更新檢查等通用設定 |
| `shop.yml` | 系統商店稅率、最低售價、購買上限重置時間、數量按鈕 |
| `auction.yml` | 拍賣可用幣別、稅率、最低售價、拍賣時間、上架數量、加價幅度、落槌、違約懲罰 |
| `messages.yml` | 所有聊天訊息、介面標題、物品名稱與說明、Discord 卡片文字 |
| `pdf.yml` | PDF 上所有文字、伺服器名稱、主題與印章顏色、字型 |
| `data/shop-items.yml` | 商店分類與商品（由遊戲內介面管理） |

## 授權

AetherMart 以 [GNU General Public License v3.0](LICENSE) 發布。

插件 jar 內含以下第三方元件，各自依其授權條款使用；授權全文與 NOTICE 放在 jar 的 `META-INF/licenses/`，清單見 [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt)。

| 元件 | 授權 |
| --- | --- |
| JDA、nv-websocket-client、OkHttp、Okio、Kotlin 標準函式庫、Jackson、Apache Commons Collections | Apache License 2.0 |
| Apache PDFBox、FontBox、pdfbox-io、Apache Commons Logging | Apache License 2.0 |
| Trove4j | GNU LGPL 2.1 |
| Noto Sans TC／SC | SIL Open Font License 1.1 |

---

# AetherMart (English)

AetherMart is an all-in-one shop plugin for Paper servers. A system shop and a player auction house share one chest UI, with Vault and ExcellentEconomy currencies, and a built-in Discord bot that sends players a PDF receipt for every trade and posts a daily summary to an admin channel.

Players only need `/mart`; everything else happens in the menus. Bedrock players (Geyser) use the same chest UI.

## Features

- **System shop**
  - **Categories and products**: admins click an item in their own inventory to add it. The full NBT is kept and the item is not taken.
  - **Per-product currency**: price each product in the default currency or the special currency.
  - **Purchase limits**: daily, weekly, total or unlimited per player, with configurable reset times.
  - **Scheduled promotions**: a chest menu where the start defaults to now and the end is one click (1 hour to 30 days), or typed as `11/1`, `11/1 20:00` or `3d`. Percentage discounts or fixed sale prices, with no overlapping periods.
  - **Tax and minimum price** per currency in `shop.yml`. The buyer pays the tax on top.
- **Player auctions**
  - **Bundle listings**: each listing is a 27-slot box sold as a whole.
  - **Buy now or bidding**: sellers choose the mode, currency (from those the admin allows), price and duration.
  - **No charge at bid time**: bids only check the balance (plus tax); money is taken when the hammer falls. Each bid must raise the price by at least 5% (configurable).
  - **Knock-down**: after the end time the hammer knocks every 5 minutes. A new bid restarts the count, and the 3rd knock sells (all configurable).
  - **Payment default**: a winner who cannot pay is banned from AetherMart for N days and fined X% of the winning bid (the fine goes to the system). Any unpaid part becomes debt, which blocks trading until paid (it is taken automatically at the next trade). The auction then goes to the previous bidder at their own bid.
  - **Claim box**: bought, won and returned items always go to the claim box and are never dropped. Income and refunds go straight to the currency account; only failed deposits wait in the claim box.
  - **Banned items**: matched on full NBT, including items inside shulker boxes and bundles.
  - **Market pause and force removal**: while paused, nobody can list, buy or bid. Force-removed items return to the seller and no bidder is charged.
- **Discord (built-in bot, just add a token)**
  - **Personal DMs with a PDF** for purchases, wins, sales, listings, removals, expiries, outbids and penalties. Players can toggle each type in their settings (penalty notices cannot be turned off).
  - **Admin channel**: a card for every trade (optional) and a daily summary PDF at the settlement time.
  - **Peak merging and rate limiting** keep the bot within Discord's limits.
  - **Link check**: reads [DiscordLink](https://github.com/clre20/Discord-link-Minecraft-Plugin) bindings (read-only). Buying or selling can require a linked account.
- **PDF receipts**
  - Traditional Chinese, Simplified Chinese and English all display correctly (Noto Sans TC / SC bundled).
  - Custom item names are shown as-is; other items use the vanilla translation, with enchantments and durability.
  - A coloured stamp per receipt type. Every text, the server name, colours and fonts are set in `pdf.yml`.
  - Daily summary PDFs are kept locally for 90 days (configurable).
- **Full activity log** in `logs/`, compressed by day or size, with retention and size limits.
- **Languages**: `zh_tw`, `zh_cn`, `en_us`. After switching and reloading, the old `messages.yml` and `pdf.yml` are backed up and rebuilt in the new language.
- **Config auto-fill**: missing keys are added on start and reload; your values and comments are kept. On upgrades, messages you never edited are replaced with the new wording.
- **Menu signature**: every menu uses a custom `InventoryHolder` (`getPlugin()`, `getMenuId()`), and menu items carry the PDC key `aethermart:gui`, so other plugins such as Item-lock can recognise them.
- **Update checker**: an async GitHub Releases check on start, cached for 10 minutes, with a join reminder 3 seconds after login for permitted admins.

## Commands & Permissions

| Command | Description | Executor | Permission | Default |
| --- | --- | --- | --- | --- |
| `/aethermart` (alias `/mart`) | Open the main menu (shop, auctions, settings) | Player | `aethermart.use` | Everyone |
| `/mart shop` | Open the system shop | Player | `aethermart.use` | Everyone |
| `/mart auction` | Open player auctions | Player | `aethermart.use` | Everyone |
| `/mart help` | Show help (only commands you can use) | Player / Console | `aethermart.help` | Everyone |
| `/mart version` | Show the version and check for updates | Player / Console | `aethermart.version` | OP |
| `/mart reload` | Reload and fill in all config files | Player / Console | `aethermart.reload` | OP |
| `/mart admin` | Open the admin menu | Player | `aethermart.admin.use` | OP |
| `/mart admin auction` | Manage auctions (force remove, pause, banned items, penalties) | Player | `aethermart.admin.auction` | OP |
| `/mart admin shop` | Manage the shop (categories, products, promotions) | Player | `aethermart.admin.shop` | OP |
| `/mart admin log [player]` | Trade log | Player | `aethermart.admin.log` | OP |
| — | Create auctions | Player | `aethermart.auction.sell` | Everyone |
| — | Raise the listing limit to n | Player | `aethermart.limit.<n>` | — |
| — | All permissions | — | `aethermart.*` | OP |

## Usage Examples

### 1. Install

```bash
# Requires Paper 1.21.x to 26.x, Java 21+, Vault and an economy plugin
# Optional: ExcellentEconomy (special currency), DiscordLink (Discord links)
plugins/
├── AetherMart-1.1.0.jar
├── Vault.jar
└── TheosisEconomy.jar
```

### 2. Set the language

```yaml
# config.yml
language: en_us
```

### 3. Add a shop product

```bash
/mart admin shop
# Add category → Products → click an item in your inventory → set price, currency, limit → Save
```

### 4. Enable Discord notices

```yaml
# config.yml
discord:
  token: "your bot token"
  admin:
    channel-id: "123456789012345678"
```

## Configuration Files

| File | Contents |
| --- | --- |
| `config.yml` | Language, time zone, currencies, chat input, Discord, logs, update checker |
| `shop.yml` | Shop tax, minimum price, limit reset times, quantity buttons |
| `auction.yml` | Allowed currencies, tax, minimum price, duration, listing limit, bid raise, knocks, penalties |
| `messages.yml` | Every chat message, menu title, item name and lore, Discord card text |
| `pdf.yml` | Every PDF text, server name, theme and stamp colours, fonts |

## License

AetherMart is released under the [GNU General Public License v3.0](LICENSE). Bundled third-party components keep their own licences (Apache 2.0, LGPL 2.1, SIL OFL 1.1); see [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt) and `META-INF/licenses/` in the jar.
