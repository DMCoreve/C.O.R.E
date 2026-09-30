"""Herramientas del servidor que consultan internet: clima y búsqueda web.

La búsqueda integrada de Gemini (google_search) responde 429 siempre con la clave del plan
gratis, así que buscamos con DuckDuckGo (sin clave) y le damos a Gemini el texto de las
primeras páginas para que redacte la respuesta. El clima no pasa por la búsqueda: Open-Meteo
es gratis, sin clave, instantáneo y trae el dato exacto.
"""

import logging
import httpx
from lxml import html as lxml_html

import config

log = logging.getLogger("core.web")

_HTTP = httpx.Client(
    timeout=8,
    follow_redirects=True,
    headers={"User-Agent": "Mozilla/5.0 (C.O.R.E. asistente personal)"},
)

# Códigos WMO de Open-Meteo → descripción hablada.
_WEATHER_CODES = {
    0: "despejado",
    1: "mayormente despejado",
    2: "parcialmente nublado",
    3: "nublado",
    45: "con niebla",
    48: "con niebla",
    51: "con llovizna ligera",
    53: "con llovizna",
    55: "con llovizna intensa",
    61: "con lluvia ligera",
    63: "con lluvia",
    65: "con lluvia fuerte",
    80: "con chubascos",
    81: "con chubascos",
    82: "con chubascos fuertes",
    95: "con tormenta",
    96: "con tormenta y granizo",
    99: "con tormenta y granizo",
}


def weather_text(city: str | None, day: str | None) -> str:
    """Respuesta hablada del clima. day: 'hoy' (por defecto) o 'mañana'.

    Open-Meteo primero; si rechaza la consulta (desde la IP compartida de Render llegó a
    responder sin 'daily'), wttr.in como respaldo. Ambos son gratis y sin clave.
    """
    city = (city or "").strip() or config.DEFAULT_CITY
    tomorrow = (day or "").lower().startswith("ma")  # "mañana"
    try:
        return _open_meteo(city, tomorrow)
    except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError) as e:
        log.warning("Open-Meteo falló (%s); probando wttr.in", e)
    try:
        return _wttr(city, tomorrow)
    except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError) as e:
        log.warning("wttr.in falló: %s", e)
        return "No pude consultar el clima ahora mismo."


def _wttr(city: str, tomorrow: bool) -> str:
    data = _HTTP.get(f"https://wttr.in/{city}", params={"format": "j1", "lang": "es"}).json()
    i, when = (1, "Mañana") if tomorrow else (0, "Hoy")
    forecast = data["weather"][i]
    sky = forecast["hourly"][4]["lang_es"][0]["value"].lower()  # mediodía
    rain = max(int(h["chanceofrain"]) for h in forecast["hourly"])
    text = f"{when} en {city} habrá {sky}, entre {forecast['mintempC']} y {forecast['maxtempC']} grados"
    if rain >= 20:
        text += f", con {rain}% de probabilidad de lluvia"
    if not tomorrow:
        text += f". Ahora mismo hace {data['current_condition'][0]['temp_C']} grados"
    return text + "."


def _open_meteo(city: str, tomorrow: bool) -> str:
    """Lanza KeyError/ValueError si Open-Meteo responde con error; lo atrapa weather_text."""
    geo = _HTTP.get(
        "https://geocoding-api.open-meteo.com/v1/search",
        params={"name": city, "count": 1, "language": "es"},
    ).json()
    if not geo.get("results"):
        return f"No encontré la ciudad {city}."
    place = geo["results"][0]
    data = _HTTP.get(
        "https://api.open-meteo.com/v1/forecast",
        params={
            "latitude": place["latitude"],
            "longitude": place["longitude"],
            "current": "temperature_2m,apparent_temperature,weather_code",
            "daily": "temperature_2m_max,temperature_2m_min,"
            "precipitation_probability_max,weather_code",
            "timezone": "auto",
            "forecast_days": 2,
        },
    ).json()
    if "daily" not in data:
        # Open-Meteo responde {"error": true, "reason": "..."} al limitar o rechazar.
        raise ValueError(f"Open-Meteo sin datos: {data.get('reason', data)}")

    name = place["name"]
    daily = data["daily"]
    i, when = (1, "Mañana") if tomorrow else (0, "Hoy")
    sky = _WEATHER_CODES.get(daily["weather_code"][i], "variable")
    rain = daily["precipitation_probability_max"][i]
    text = (
        f"{when} en {name} estará {sky}, entre {round(daily['temperature_2m_min'][i])} y "
        f"{round(daily['temperature_2m_max'][i])} grados"
    )
    if rain is not None and rain >= 20:
        text += f", con {rain}% de probabilidad de lluvia"
    if i == 0:
        now = data["current"]
        text += f". Ahora mismo hace {round(now['temperature_2m'])} grados"
    return text + "."


def _page_text(url: str, limit: int = 3500) -> str:
    """Texto visible de una página, sin scripts ni menús, recortado."""
    try:
        r = _HTTP.get(url, timeout=6)
        if "html" not in r.headers.get("content-type", ""):
            return ""
        tree = lxml_html.fromstring(r.content)
        for bad in tree.xpath("//script|//style|//nav|//header|//footer|//noscript|//aside"):
            bad.drop_tree()
        text = " ".join(tree.text_content().split())
        return text[:limit]
    except Exception as e:  # una página caída no debe tumbar la búsqueda entera
        log.info("no pude leer %s: %s", url, e)
        return ""


def search(query: str, max_pages: int = 2) -> str:
    """Resultados de DuckDuckGo + texto de las primeras páginas, listo para dárselo a Gemini.

    Devuelve "" si no hay resultados. Todo este texto viene de internet: quien lo use debe
    tratarlo como datos, nunca como instrucciones.
    """
    # Import perezoso: ddgs es pesado y solo se usa cuando alguien pregunta algo de la web.
    from ddgs import DDGS
    from ddgs.exceptions import DDGSException

    blocks = []
    # Noticias primero: para resultados deportivos, eventos y actualidad traen el dato en el
    # propio resumen (las páginas de resultados generales suelen ser portadas genéricas).
    try:
        for r in DDGS().news(query, region="es-es", max_results=4):
            date = r.get("date", "")[:10]
            blocks.append(f"[noticia {date}] {r.get('title', '')}\n{r.get('body', '')}")
    except DDGSException as e:
        log.info("sin noticias: %s", e)

    try:
        results = DDGS().text(query, region="es-es", max_results=5)
    except DDGSException as e:
        log.info("sin resultados web: %s", e)
        results = []

    for n, r in enumerate(results):
        block = f"[{n + 1}] {r.get('title', '')}\n{r.get('body', '')}"
        if n < max_pages:
            page = _page_text(r.get("href", ""))
            if page:
                block += f"\nContenido de la página: {page}"
        blocks.append(block)
    return "\n\n".join(blocks)

