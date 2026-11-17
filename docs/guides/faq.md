# 常見問題

[English](faq.en.md)

### XenoBrex 要錢嗎？跟 aRacer 有關係嗎？

不用錢，GPL-3.0 開源。這是非官方的第三方工具，跟 aRacer 沒有關係，也沒有經過 aRacer 授權。

### 為什麼要自己準備定義檔和金鑰？

那是 aRacer 的東西，我們不散布。從你自己的 XTune 安裝檔匯入就好，見[第一次使用](getting-started.md#2-匯入定義檔和金鑰)。

### 能不能打開 aRacer 沒開放給我這個型號的功能？

不行。aRacer 沒開放給這個型號的付費功能和表，XenoBrex 不會解鎖，也不會改密碼、燒韌體或動啟用碼。

「顯示進階表」只會列出定義檔裡這個型號本來就有、只是 XTune 沒顯示的表。

### aRacer 會不會偵測到、把 ECU 鎖住？

我們沒辦法保證 aRacer 以後會怎麼做，但目前知道的是：

- XenoBrex 跟 ECU 溝通的方式和 XTune 一樣：一樣的登入、一樣的讀寫表。
- 我們沒有發現 ECU 會記錄值是哪個 App 寫進去的。
- 只在電腦上編輯檔案，完全不會碰到 ECU。

排除不了的是之後 XTune 或韌體更新後的情況，所以連線時存的備份請留好。想更保險的話，可以先只編輯檔案，有把握了再寫進 ECU。

### 連線 ECU 是灰的，按不了

要先開一個調校檔。見[連線 ECU 即時調校](live-tuning.md#連線前)。

### 連不上 ECU

- 電腦有沒有連上 ECU 的 Wi-Fi？鑰匙有沒有轉到 ON？
- 手機的 XTune 有沒有關掉？ECU 一次只接受一個連線。
- 密碼改過的話，要填你改過的密碼。

### 手機版可以用嗎？

可以，手機用 [ReX](https://github.com/xeno-hf/ReX)：即時儀表、錄製，跟 XenoBrex 配對後還能即時調校（目前只有 Android 版）。

### 我想提建議或回報問題

- 問題、想法、心得：[Discussions](https://github.com/xeno-hf/XenoBrex/discussions)
- 確定是程式錯誤：[Issue](https://github.com/xeno-hf/XenoBrex/issues)，請附上截圖和紀錄檔（位置見[連線 ECU 即時調校](live-tuning.md#如果出現ecu-需要復原)）。
