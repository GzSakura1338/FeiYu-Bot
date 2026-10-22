import json
import urllib.parse
import urllib.request

from feiyu_plugin import Plugin

p = Plugin()

CODES = {
    0: "晴", 1: "大致晴朗", 2: "局部多云", 3: "阴", 45: "雾", 48: "雾凇", 51: "小毛毛雨", 53: "毛毛雨", 55: "大毛毛雨",
    61: "小雨", 63: "中雨", 65: "大雨", 66: "冻雨", 67: "强冻雨", 71: "小雪", 73: "中雪", 75: "大雪", 77: "米雪",
    80: "阵雨", 81: "强阵雨", 82: "暴雨", 85: "阵雪", 86: "强阵雪", 95: "雷阵雨", 96: "雷阵雨伴冰雹", 99: "强雷阵雨伴冰雹",
}


def fetch(url):
    with urllib.request.urlopen(url, timeout=12) as response:
        return json.loads(response.read().decode("utf-8"))


@p.tool("current", "查某个城市现在的天气", {"city": {"type": "string"}})
def current(city):
    query = urllib.parse.urlencode({"name": city, "count": 1, "language": "zh"})
    places = fetch("https://geocoding-api.open-meteo.com/v1/search?" + query).get("results") or []
    if not places:
        return f"没找到叫「{city}」的地方"
    place = places[0]
    query = urllib.parse.urlencode({
        "latitude": place["latitude"], "longitude": place["longitude"], "timezone": "auto", "forecast_days": 2,
        "current": "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m",
        "daily": "temperature_2m_max,temperature_2m_min,weather_code",
    })
    data = fetch("https://api.open-meteo.com/v1/forecast?" + query)
    now = data["current"]
    daily = data["daily"]
    where = "，".join(x for x in [place.get("country"), place.get("admin1"), place.get("name")] if x)
    lines = [
        f"{where}（当地时间 {now['time'].replace('T', ' ')}）",
        f"现在 {CODES.get(now['weather_code'], '未知')}，{now['temperature_2m']}°C，体感 {now['apparent_temperature']}°C，"
        f"湿度 {now['relative_humidity_2m']}%，风速 {now['wind_speed_10m']} km/h",
    ]
    for i, label in enumerate(["今天", "明天"]):
        lines.append(f"{label} {CODES.get(daily['weather_code'][i], '未知')}，{daily['temperature_2m_min'][i]}～{daily['temperature_2m_max'][i]}°C")
    return "\n".join(lines)


p.run()
