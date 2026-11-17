# XenoBrex

繁體中文 | [English](README.en.md)

aRacer RC X 系列 ECU 的桌面版調校工具（Windows／macOS／Linux）：開調校檔、看表、改表、比較，也能用 Wi-Fi 連上 ECU 即時調校。

**非官方**第三方工具，與 aRacer 無關。不改密碼、不燒錄韌體。

## 功能

- 開啟、編輯、比較、另存調校檔（AMX／ASX／AMX2…）：加減乘除、設值、LINEAR、SMOOTH、復原；可改原始值
- 連上 ECU：先整份讀回並自動備份；改表停手約 1.2 秒寫入並讀回確認；全讀／全寫；把整份調校寫回 ECU；用這次連線驗證過的完整備份還原
- 油圖上顯示引擎目前的位置（ECU 或手機 ReX 的即時資料）
- 功能專頁：怠速、TPS 學習、快排、eBoost、回火、自動暖車、自動調校等（依 ECU 型號）
- 用命令列 `xbrex convert` 轉換到其他版本或車種：先從 aRacer 官方伺服器下載目標的原廠檔當基準，列出需要確認的表
- 當手機 [ReX](https://github.com/xeno-hf/ReX) 的同步伺服器：配對碼配對、加密連線；給 ReX 調校包、收錄製檔
- `.xbx` 專案包（調校檔＋備註＋修改紀錄＋ECU 備份）、`.xbl` 騎乘紀錄（命令列 `xbrex ride` 檢視與匯出）
- 命令列工具 `xbrex`（英文），和桌面版裝在一起：轉換版本／車種、檢查與匯出 `.xbl`、連線讀回與備份
- 介面：English、繁體中文、简体中文、日本語

## 安裝

目前先提供原始碼；Windows `.msi`、macOS（Apple Silicon）`.dmg`、Linux `.tar.gz` 安裝檔正在準備，完成後會放到 [Releases](https://github.com/xeno-hf/XenoBrex/releases/latest)。

從原始碼安裝（需要 JDK 21）：

```sh
git clone https://github.com/xeno-hf/XenoBrex.git
cd XenoBrex
./install.sh
```

之後提供的安裝檔不會簽章，Windows 和 macOS 第一次開啟要手動放行。

## 定義檔與金鑰

XenoBrex 不附 aRacer 的校正定義檔（ControlFile）和金鑰，要用自己的：

- 從自己的 XTune（Android 版）安裝檔（`.xapk`／`.apks`）匯入：程式只讀檔案，不執行裡面的程式。
- 或自己選定義檔資料夾、貼上金鑰。

程式另外內建一份監控通道表（通道名稱、編號、換算、單位與預設頻道），只供即時顯示、記錄與重播使用，不能當校正定義檔開檔；詳見 [NOTICE.md](NOTICE.md)。

`sample/` 有一組人工編的示範檔（不是任何 ECU 的資料），可以先拿來試：

```sh
XB_DEFINITIONS=sample/definitions ./gradlew :xb-app:run --args="sample/Demo.amx"
```

## 安全

- 連上 ECU 會先整份讀回存備份；調校檔的版本或型號跟 ECU 不同就不寫；ECU 拒絕的表不再送進 ECU（改了只留在檔案裡）；可以用這次連線驗證過的完整備份寫回。
- 改表會直接改變引擎的行為。請在安全的地方、停車狀態下調校，改過的設定自己負責。
- **所有實車寫入與還原路徑（即時調校、整份寫回、備份還原）都尚未在實車上驗證**；第一次使用前請先確認備份與還原流程。

## 建置

需要 JDK 21。

```sh
./gradlew build                # 建置並跑測試
./gradlew :xb-app:run          # 執行
./gradlew :xb-app:packageMsi   # 打包；macOS 用 packageDmg，Linux 用 packageTarGz
```

## 模組

| 模組 | 內容 |
|---|---|
| `xb-tune` | 調校檔核心：讀檔、換算、編輯、存檔、比較、轉換 |
| `xb-container` | 檔案層：加解密、定義檔、開檔與存檔、專案包、官方原廠檔 |
| `xb-link` | 連 ECU：登入、讀寫、整份讀回與比對、即時調校 |
| `xb-sync` | 跟 ReX 同步 |
| `xb-calui` | 功能專頁（ReX 也用同一份） |
| `xb-cli` | 命令列工具 `xbrex` |
| `xb-app` | 桌面版（Compose Multiplatform） |
| `rx-protocol`、`rx-defs`、`rx-session` | 與 ReX 共用的協定、即時通道與同步 |

## 授權

GPL-3.0，見 [LICENSE](LICENSE)。字型 Oxanium 採 SIL Open Font License。商標聲明見 [NOTICE.md](NOTICE.md)。
