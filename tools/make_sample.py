#!/usr/bin/env python3
"""產生 sample/ 裡的示範檔：一組自己編的定義檔與一份調校檔。

內容全部是人工編的（表名、尺寸、數值都不是任何實際 ECU 的資料），
只是照調校檔的檔案格式寫，讓沒有定義檔和金鑰的人也能把程式開起來看，
也讓自動測試在任何機器上都有檔案可以開。未加密。
"""
import math
from pathlib import Path

root = Path(__file__).resolve().parent.parent / "sample"
defs = root / "definitions"
defs.mkdir(parents=True, exist_ok=True)

MODEL = 10      # Table_ID 第三段
C_CODE = 7

def tinf(i, x, y, cell=1, mark="0", sx="0", sy="0", mx="255", mn="0", slew="1", gain="1", off="0", unit="_", group="Fuel_Cal", dec="0"):
    f = [i, x, y, cell, 0, mark, sx, sy, mx, mn, slew, gain, off, "_", unit, group, dec] + ["_"] * 16
    return ",".join(str(v) for v in f)

# (表號, 內部名稱, 定義欄位, 等級, 短名, 說明)
TABLES = [
    (1, "Demo_Fuel_Map", tinf(1, 12, 8, sx="Dyna-2-RPM", sy="LOAD-1", mx="200", mn="0", unit="%", group="Fuel_Cal"), 1, "示範燃油表", "示範用：橫軸轉速、縱軸負載的燃油修正（%）。"),
    (2, "Demo_RPM_Axis", tinf(2, 12, 0, cell=2, mx="16000", mn="0", slew="100", unit="rpm", group="Fuel_Cal"), 1, "示範轉速軸", "示範燃油表與示範點火表共用的轉速軸，可以改。"),
    (3, "Demo_Target_AFR", tinf(3, 12, 8, sx="Dyna-2-RPM", sy="LOAD-1", mx="20", mn="10", slew="0.1", gain="10", unit="AFR", group="Target_AFR", dec="1"), 1, "示範目標空燃比", "示範用：目標空燃比。"),
    (4, "Demo_Spark_Map", tinf(4, 12, 8, sx="Dyna-2-RPM", sy="LOAD-1", mx="60", mn="-10", slew="0.5", gain="2", off="20", unit="deg", group="SA_Cal", dec="1"), 1, "示範點火表", "示範用：點火提前角（度）。"),
    (5, "Demo_Rev_Limit", tinf(5, 0, 0, cell=2, mx="16000", mn="3000", slew="100", unit="rpm", group="RPM_Limiter"), 1, "示範斷油轉速", "示範用：單一數值。"),
    (6, "Demo_Warmup", tinf(6, 8, 0, sx="TEMP-1", mx="200", mn="50", unit="%", group="Warm_Up_Cal"), 1, "示範暖車補償", "示範用：依水溫的一列補償。"),
    (7, "Demo_Switch", tinf(7, 0, 0, mx="1", mn="0", group="Sys_Cal"), 1, "示範開關", "示範用：0／1 開關。"),
    (8, "Demo_Advanced", tinf(8, 4, 0, mx="255", mn="0", group="Others"), 4, "示範進階表", "示範用：要打開「進階表」才看得到。"),
]

def control_file():
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<ECUControlFile>", '<ControlFileSerial sn="1"/>', "<Parameters>"]
    for i, name, t, level, _, _ in TABLES:
        out.append(f'<Tab name="{name}" absid="{i}"><Tinf>{t}</Tinf>')
        out.append(f'<Ver c_version="{C_CODE}" status="Enable"><Model name="Demo" mid="{MODEL}">{level}</Model></Ver>')
        out.append("</Tab>")
    out += ["</Parameters>", "</ECUControlFile>", ""]
    return "\n".join(out)

LOAD = [20, 30, 40, 50, 60, 70, 85, 100]
TEMP = [-10, 0, 20, 40, 60, 80, 100, 120]
RPM = [1000, 1500, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000, 11000]

def scale_data():
    return (
        "<TableScale><Version>1</Version>\n"
        f'<LOAD><scale size="{len(LOAD)}" value="{",".join(map(str, LOAD))}">LOAD-1</scale></LOAD>\n'
        f'<TEMP><scale size="{len(TEMP)}" value="{",".join(map(str, TEMP))}">TEMP-1</scale></TEMP>\n'
        "</TableScale>\n"
    )

GROUPS = {
    "Fuel_Cal": ("燃油", "Fuel"), "Target_AFR": ("目標空燃比", "Target AFR"), "SA_Cal": ("點火", "Spark"),
    "RPM_Limiter": ("轉速限制", "Rev limiter"), "Warm_Up_Cal": ("暖車", "Warm-up"), "Sys_Cal": ("系統", "System"), "Others": ("其他", "Others"),
}

def lang(zh, en):
    return f"<zh-TW>{zh}</zh-TW><en-US>{en}</en-US>"

def group_des():
    return "<Groups>\n" + "".join(f'<Group name="{k}">{lang(*v)}</Group>\n' for k, v in GROUPS.items()) + "</Groups>\n"

def short_names():
    return "<Tabs>\n" + "".join(f'<Tab absid="{i}">{lang(short, name)}</Tab>\n' for i, name, _, _, short, _ in TABLES) + "</Tabs>\n"

def descriptions():
    return "<Des><Table>\n" + "".join(f'<Tab absid="{i}">{lang(desc, name)}</Tab>\n' for i, name, _, _, _, desc in TABLES) + "</Table></Des>\n"

def rows(table):
    return "".join(",".join(str(v) for v in r) + ",\n" for r in table)

def tune():
    fuel = [[round(100 + 12 * math.sin(c / 3.0) + (r - 3) * 1.5) for c in range(12)] for r in range(8)]
    afr = [[round((14.7 - r * 0.25 - (0.4 if c > 7 else 0)) * 10) for c in range(12)] for r in range(8)]
    spark = [[round((12 + c * 2.2 - r * 1.5) * 2 + 20) for c in range(12)] for r in range(8)]
    warm = [[150, 140, 125, 112, 104, 100, 100, 100]]
    body = [
        ("Demo_Fuel_Map", 1, rows(fuel)), ("Demo_RPM_Axis", 2, rows([RPM])), ("Demo_Target_AFR", 3, rows(afr)),
        ("Demo_Spark_Map", 4, rows(spark)), ("Demo_Rev_Limit", 5, "10500\n"), ("Demo_Warmup", 6, rows(warm)),
        ("Demo_Switch", 7, "1\n"), ("Demo_Advanced", 8, rows([[1, 2, 3, 4]])),
    ]
    out = ['<?xml version="1.0" encoding="UTF-8"?><AMC_File>', "<MAP_Type>AMX</MAP_Type>", "<V_Code>1</V_Code>", f"<C_Code>{C_CODE}</C_Code>",
           "<A_Code>1</A_Code>", f"<Table_ID>00_00_{MODEL}_01</Table_ID>", "<LastSaveTime>2026-01-01 00:00:00</LastSaveTime>",
           "<CarNote>XenoBrex sample. Made-up values, not for any real ECU.</CarNote>", "<IO_Config>", "0,0,", "</IO_Config>", "<TableValue>"]
    for name, i, text in body:
        out.append(f'<Table name="{name}" absid="{i}">\n{text}</Table>')
    out += ["</TableValue>", "</AMC_File>", ""]
    return "\n".join(out)

(defs / "ControlFile_S3.xml").write_text(control_file(), encoding="utf-8")
(defs / "ScaleData.xml").write_text(scale_data(), encoding="utf-8")
(defs / "XECU_GroupDes.xml").write_text(group_des(), encoding="utf-8")
(defs / "XECU_TabShortName.xml").write_text(short_names(), encoding="utf-8")
(defs / "XECU_ParaAndVarDes.xml").write_text(descriptions(), encoding="utf-8")
(root / "Demo.amx").write_text(tune(), encoding="utf-8")
print("wrote", root)
