# -*- coding: utf-8 -*-
"""
拼音首字母
==========
电视上没有实体键盘，遥控器只能点字母。要在「拼音首字母」上做搜索，
索引里就得先存好每个应用名的首字母串（如 爱奇艺 → aqy）。

不装第三方库：用 GB2312 一级汉字按拼音排序这个特性直接查表，
Actions 里零依赖，几毫秒跑完。

    from pinyin import initials
    initials("奇异果TV")   # -> 'qygtv'
"""

# GB2312 一级汉字按拼音升序排列的区位码分段起点
_BOUND = [
    (0xB0A1, "a"), (0xB0C5, "b"), (0xB2C1, "c"), (0xB4EE, "d"),
    (0xB6EA, "e"), (0xB7A2, "f"), (0xB8C1, "g"), (0xB9FE, "h"),
    (0xBBF7, "j"), (0xBFA6, "k"), (0xC0AC, "l"), (0xC2E8, "m"),
    (0xC4C3, "n"), (0xC5B6, "o"), (0xC5BE, "p"), (0xC6DA, "q"),
    (0xC8BB, "r"), (0xC8F6, "s"), (0xCBFA, "t"), (0xCDDA, "w"),
    (0xCEF4, "x"), (0xD1B9, "y"), (0xD4D1, "z"),
]

# 多音字/常用品牌名的特殊读法，避免搜不到
_OVERRIDE = {
    "重庆": "cq", "银行": "yh", "快乐": "kl", "长大": "zd",
    "重要": "zy", "重新": "cx", "行为": "xw",
    # 二级汉字（不在 GB2312 一级区）读不出来，这几个常用品牌直接写死
    "哔哩哔哩": "blbl", "哔哩": "bl", "音乐": "yy", "爱奇艺": "aqy",
    "奇异果": "qyg", "咪咕": "mg", "优酷": "yk", "芒果": "mg",
    "当贝": "db", "电视": "ds", "视频": "sp", "投屏": "tp",
    "宝宝": "bb", "巴士": "bs", "酷喵": "km", "云视听": "yst",
}


def _first_letter(ch):
    try:
        b = ch.encode("gb2312")
    except Exception:
        return ""
    if len(b) != 2:
        return ""
    code = (b[0] << 8) | b[1]
    if code > 0xD7F9:      # 一级汉字结束，二级/三级汉字查不准，宁可留空
        return ""
    for i in range(len(_BOUND) - 1, -1, -1):
        if code >= _BOUND[i][0]:
            return _BOUND[i][1]
    return ""


def initials(name):
    """取每个汉字的首字母，英文字母和数字原样保留，其它字符忽略"""
    if not name:
        return ""
    out = []
    i = 0
    n = len(name)
    while i < n:
        ch = name[i]
        # 先看有没有多音字词组，命中就整段取
        hit = None
        for k, v in _OVERRIDE.items():
            if name.startswith(k, i):
                hit = (k, v)
                break
        if hit:
            out.append(hit[1])
            i += len(hit[0])
            continue
        if ch.isascii() and ch.isalnum():
            out.append(ch.lower())
        else:
            out.append(_first_letter(ch))
        i += 1
    return "".join(out)


if __name__ == "__main__":
    for s in ["爱奇艺", "奇异果TV", "腾讯视频TV版", "芒果TV", "哔哩哔哩TV版",
              "宝宝巴士", "当贝投屏", "QQ音乐TV版", "音乐", "电视猫视频"]:
        print("%-14s -> %s" % (s, initials(s)))
