# Look4Sat Chinese Translation Completion

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Complete the Chinese (zh) translation of Look4Sat by adding ~60 missing strings that were introduced in versions after 4.0, while preserving the existing 154-line translation.

**Architecture:** Single file change — update `values-zh/strings.xml` to match the English `values/strings.xml` with all missing strings translated.

**Tech Stack:** Android XML resource files.

---

## Current Context

- **Existing Chinese translation:** `core/presentation/src/main/res/values-zh/strings.xml` — 154 lines, covers basic UI but missing newer features
- **English reference:** `core/presentation/src/main/res/values/strings.xml` — 216 lines
- **Missing strings:** Doppler calculator, CW decoder, CAT radio control, data import errors, and several settings strings
- **Existing translations preserved:** All 154 lines of existing Chinese are good and kept as-is

### Strings already in Chinese (kept unchanged)

All existing strings from `values-zh/strings.xml` — navigation, satellite screen, passes screen, radar screen (basic), map screen, settings (station, data, network, bluetooth, other), highlight, outro.

### Strings missing from Chinese (need translation)

| Name | English | Chinese Translation |
|------|---------|-------------------|
| `radar_doppler_calc` | Doppler Frequency Calculator | 多普勒频率计算器 |
| `radar_doppler_tx_hint` | Enter TX freq (MHz) | 输入上行频率 (MHz) |
| `radar_doppler_rx_hint` | Enter RX freq (MHz) | 输入下行频率 (MHz) |
| `radar_doppler_offset_hint` | Offset (kHz) | 偏移 (kHz) |
| `radar_doppler_info` | For linear transponders, type one frequency to see the other | 线性转发器：输入一个频率即可算出另一个 |
| `radar_cw_decoder` | CW Decoder | CW 解码器 |
| `radar_cw_start` | Start | 开始 |
| `radar_cw_stop` | Stop | 停止 |
| `radar_cw_reset` | Clear | 清空 |
| `prefs_donate_title` | Donate | 支持（已有） |
| `prefs_privacy_title` | Privacy Policy | 隐私政策（已有） |
| `prefs_data_import_satellites_error` | No satellites imported. Select a valid TLE/3LE (.txt) or OMM (.csv) file. | 未导入卫星。请选择有效的 TLE/3LE (.txt) 或 OMM (.csv) 文件。 |
| `prefs_data_import_transceivers_error` | No transceivers imported. Select a valid SatNOGS (.json) file. | 未导入收发器。请选择有效的 SatNOGS (.json) 文件。 |
| `nav_radiocontrol` | Radio Control | 电台控制 |
| `rc_settings_title` | CAT Radio Control | CAT 电台控制 |
| `rc_radio_model` | Radio Model | 电台型号 |
| `rc_tx_device_hint` | TX Radio BT Address | 发射电台蓝牙地址 |
| `rc_rx_device_hint` | RX Radio BT Address | 接收电台蓝牙地址 |
| `rc_tx_name_hint` | TX Radio Name | 发射电台名称 |
| `rc_rx_name_hint` | RX Radio Name | 接收电台名称 |
| `rc_enable_switch` | Enable CAT Control | 启用 CAT 控制 |
| `prefs_cat_output` | CAT | CAT |
| `prefs_outro_thanks` | (updated version with xdsopl and Robot36) | 在现有翻译末尾加上 `\\n* xdsopl 和 Robot36 贡献者!` |

---

## Step-by-step Plan

### Task 1: Update values-zh/strings.xml with all missing strings

**Objective:** Add all missing Chinese translations to the existing file, preserving existing translations.

**Files:**
- Modify: `core/presentation/src/main/res/values-zh/strings.xml`

**Changes needed:**

1. **Add Doppler calculator strings** (after `radar_visible`):
```xml
    <string name="radar_doppler_calc">多普勒频率计算器</string>
    <string name="radar_doppler_tx_hint">输入上行频率 (MHz)</string>
    <string name="radar_doppler_rx_hint">输入下行频率 (MHz)</string>
    <string name="radar_doppler_offset_hint">偏移 (kHz)</string>
    <string name="radar_doppler_info">线性转发器：输入一个频率即可算出另一个</string>
```

2. **Add CW decoder strings** (after Doppler):
```xml
    <string name="radar_cw_decoder">CW 解码器</string>
    <string name="radar_cw_start">开始</string>
    <string name="radar_cw_stop">停止</string>
    <string name="radar_cw_reset">清空</string>
```

3. **Add data import error strings** (after `prefs_data_update_success`):
```xml
    <string name="prefs_data_import_satellites_error">未导入卫星。请选择有效的 TLE/3LE (.txt) 或 OMM (.csv) 文件。</string>
    <string name="prefs_data_import_transceivers_error">未导入收发器。请选择有效的 SatNOGS (.json) 文件。</string>
```

4. **Add CAT radio control section** (after `prefs_bt_output`):
```xml
    <string name="prefs_cat_output">CAT</string>
```

5. **Add radio control section** (after `prefs_cat_output`):
```xml
    <!-- Radio Control -->
    <string name="nav_radiocontrol">电台控制</string>
    <string name="rc_settings_title">CAT 电台控制</string>
    <string name="rc_radio_model">电台型号</string>
    <string name="rc_tx_device_hint">发射电台蓝牙地址</string>
    <string name="rc_rx_device_hint">接收电台蓝牙地址</string>
    <string name="rc_tx_name_hint">发射电台名称</string>
    <string name="rc_rx_name_hint">接收电台名称</string>
    <string name="rc_enable_switch">启用 CAT 控制</string>
```

6. **Update `prefs_outro_thanks`** to include the new contributors:
```xml
    <string name="prefs_outro_thanks" translatable="false">
        • Look4Sat 所有用户及贡献者!
        \n• David A. B. Johnson (predict4java)
        \n• Dave Moten (predict4java)
        \n• Alexandru Csete (Gpredict)
        \n• Dr T.S. Kelso (Celestrak)
        \n• Libre Space Foundation (SatNOGS)
        \n• xdsopl 和 Robot36 贡献者!
    </string>
```

**Step 1: Verify the file compiles**

Since we're modifying an Android resource XML, verify it's valid by building:

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL

**Step 2: Verify Chinese is loaded**

The app should now show Chinese text when the system language is set to Chinese. No code changes are needed since Android's resource system automatically picks the `values-zh` folder.

**Step 3: Commit**

```bash
git add core/presentation/src/main/res/values-zh/strings.xml
git commit -m "i18n(zh): complete Chinese translation with new features

- Add Doppler frequency calculator strings
- Add CW decoder strings
- Add CAT radio control strings
- Add data import error messages
- Update outro thanks with new contributors
- Preserve all existing 154 lines of translation"
```

---

## Files changed

| File | Action |
|------|--------|
| `core/presentation/src/main/res/values-zh/strings.xml` | Modify (add ~60 lines) |

## Verification

1. Build: `./gradlew :app:assembleDebug --no-daemon` → BUILD SUCCESSFUL
2. Install APK on device with system language set to Chinese
3. Check all screens show Chinese text:
   - Satellite screen: 搜索、类型、全选等
   - Passes screen: 过境、仰角、AOS 等
   - Radar screen: 多普勒频率计算器、CW 解码器
   - Settings: CAT 电台控制、数据导入错误信息
   - Map: 方位角、仰角、高度等

## Risks / tradeoffs

- **None.** This is a pure translation update with no code changes. Existing translations are preserved.
- The `prefs_outro_thanks` string is marked `translatable="false"` in the English version (because it contains proper names), but in the Chinese version it was already translated, so we keep it translated.
- The `app_name` is `translatable="false"` in English, so it's not included in the Chinese file.