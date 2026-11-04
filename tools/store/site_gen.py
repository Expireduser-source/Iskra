import json, pathlib
CSS = """
:root{--bg:#1c1424;--card:#2a1f3a;--ink:#f4eefa;--muted:#c9b8e4;--accent:#9b6fe0;--btn:#7a4fb0;--line:#3b2d50}
@media (prefers-color-scheme: light){:root{--bg:#fbf8ff;--card:#ffffff;--ink:#22172f;--muted:#5a4b70;--accent:#7a4fb0;--btn:#7a4fb0;--line:#e6dcf5}}
*{box-sizing:border-box}html{scroll-behavior:smooth}
body{margin:0;background:var(--bg);color:var(--ink);font:17px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans",sans-serif}
a{color:var(--accent)}.wrap{max-width:1040px;margin:0 auto;padding:0 16px}
nav{display:flex;justify-content:space-between;align-items:center;padding:14px 0}nav b{font-size:20px}
nav .links a{margin-left:16px;text-decoration:none}
.hero{display:grid;grid-template-columns:1.1fr .9fr;gap:24px;align-items:center;padding:24px 0 8px}
.hero h1{font-size:clamp(34px,6vw,56px);line-height:1.05;margin:0 0 12px}.hero p{color:var(--muted);font-size:19px;margin:0 0 20px}
.hero img.char{width:100%;max-width:420px;justify-self:center;filter:drop-shadow(0 18px 40px rgba(122,79,176,.45))}
.btn{display:inline-block;background:var(--btn);color:#fff;text-decoration:none;padding:15px 26px;border-radius:30px;font-weight:700;font-size:18px}
.btn.ghost{background:transparent;color:var(--accent);border:2px solid var(--accent);padding:13px 22px;margin-left:8px}
.small{font-size:14px;color:var(--muted)}
.qr{display:flex;gap:14px;align-items:center;margin-top:22px}.qr img{width:112px;height:112px;border-radius:10px;background:#fff;padding:6px}
section{padding:30px 0}h2{font-size:30px;margin:0 0 16px}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:18px}
.card img{width:84px;height:84px;float:right;margin:-6px -6px 4px 8px}.card h3{margin:0 0 6px;font-size:19px}
.card p{margin:0;color:var(--muted);font-size:15.5px}
ol.steps{padding-left:22px}ol.steps li{margin:6px 0}
details{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:12px 16px;margin:10px 0}
summary{cursor:pointer;font-weight:600}
footer{border-top:1px solid var(--line);margin-top:30px;padding:22px 0 40px;color:var(--muted);font-size:14px}
@media (max-width:760px){.hero{grid-template-columns:1fr}.hero img.char{max-width:300px;order:-1}.grid{grid-template-columns:1fr}.btn.ghost{margin:10px 0 0}}
"""
APK = "https://github.com/Expireduser-source/Iskra/releases/latest/download/Iskra.apk"
REPO = "https://github.com/Expireduser-source/Iskra"
T = {
 "en": dict(lang="en", title="Iskra — a cat-girl companion for Android", other=("Русский", "ru/"), img="img/", root="",
  desc="A cute animated companion that lives on your screen: alarms, reminders, notes, weather and chat. Free, no ads, private.",
  h1="Iskra lives on your screen", lead="A cute cat-girl companion for Android: she sets alarms and reminders, keeps notes, tells the weather and chats with you — free, without ads or accounts, and your data stays on the phone.",
  dl="📥 Download for Android", src="Source code", note="Android 8–16 · English and Russian · free and open source (GPL-3.0)",
  qr="On a computer? Scan with your phone's camera to download.",
  feat="What she can do", cards=[
   ("iskra_wave","Right away, offline","Alarms, timers, reminders that survive a restart, notes, the weather, the flashlight, apps — just say or type it."),
   ("iskra_phone","A free brain in one tap","Connect OpenRouter with Google or e-mail — nothing to copy. Or bring your own key: DeepSeek, OpenAI, Claude."),
   ("iskra_joy","Voice and hearing","Natural cloud voices or the system one. Say “Iskra” or “Spark” — recognized right on the phone."),
   ("iskra_thoughtful","From any app","Share text to Iskra or select it → “Ask Iskra”: explain, summarize, translate, note, remind."),
   ("iskra_sit","Every phone and person","Guides for Xiaomi, Samsung, Huawei, OPPO, vivo and more; TalkBack, large fonts, light and dark, tablets."),
   ("iskra_sleep","Private by design","Memory, notes and the chat stay on the phone. Keys encrypted. No ads, no trackers, daily spending limits.")],
  how="Install in a minute", steps=[f'Open <a href="{APK}">Iskra.apk</a> on your phone — it\'s always the newest version.',
   "Open the downloaded file. If Android asks, allow installing apps from your browser.",
   "Iskra introduces herself in four short steps. Every permission is optional."],
  faq="Questions", qa=[("Is it really free?","Yes. Commands, memory, notes, reminders and weather cost nothing. The free OpenRouter brain gives about 50 messages a day; paid services cost fractions of a cent per reply, with daily limits you set."),
   ("Is it safe to install an APK?","The code is open, every build is made by GitHub Actions from the public repository, and the APK is signed with the project's own key. A .sha256 checksum is published next to it."),
   ("What data leaves my phone?",f'Only what a feature you use needs: your message for the AI model you chose, the reply text for a cloud voice, the city for the weather. Full table: <a href="{REPO}/blob/main/PRIVACY.md">privacy policy</a>.'),
   ("Is she a person?","No — Iskra is an AI program, and she says so if you ask.")],
  foot=f'Iskra is free software under the GNU GPL v3. Character artwork © the project owner. Weather data by <a href="https://open-meteo.com">Open-Meteo</a>. · <a href="{REPO}">GitHub</a> · <a href="{REPO}/blob/main/PRIVACY.md">Privacy</a> · <a href="{REPO}/issues">Report a problem</a>'),
 "ru": dict(lang="ru", title="Искра — котодевочка-компаньон для Android", other=("English", "../"), img="../img/", root="../",
  desc="Милый анимированный компаньон на экране: будильники, напоминания, заметки, погода и болтовня. Бесплатно, без рекламы, приватно.",
  h1="Искра живёт на твоём экране", lead="Милая котодевочка-компаньон для Android: ставит будильники и напоминания, ведёт заметки, рассказывает погоду и болтает с тобой — бесплатно, без рекламы и аккаунтов, а данные остаются на телефоне.",
  dl="📥 Скачать для Android", src="Исходный код", note="Android 8–16 · русский и английский · бесплатно и с открытым кодом (GPL-3.0)",
  qr="Открыл с компьютера? Наведи камеру телефона — начнётся скачивание.",
  feat="Что она умеет", cards=[
   ("iskra_wave","Сразу и без интернета","Будильники, таймеры, напоминания, которые переживут перезагрузку, заметки, погода, фонарик, приложения — просто скажи или напиши."),
   ("iskra_phone","Бесплатный «мозг» в одну кнопку","Подключи OpenRouter через Google или почту — копировать ничего не нужно. Или свой ключ: DeepSeek, OpenAI, Claude."),
   ("iskra_joy","Голос и слух","Живые облачные голоса или системный. Скажи «Искра» — распознавание прямо на телефоне."),
   ("iskra_thoughtful","Из любого приложения","«Поделиться → Искре» или выдели текст → «Спросить Искру»: объяснить, пересказать, перевести, в заметки, напомнить."),
   ("iskra_sit","Для любого телефона и человека","Подсказки для Xiaomi, Samsung, Huawei, OPPO, vivo и других; TalkBack, крупный шрифт, светлая и тёмная тема, планшеты."),
   ("iskra_sleep","Приватность по умолчанию","Память, заметки и переписка — на телефоне. Ключи зашифрованы. Без рекламы и трекеров, дневные лимиты расходов.")],
  how="Установка за минуту", steps=[f'Открой на телефоне <a href="{APK}">Iskra.apk</a> — это всегда самая новая версия.',
   "Открой скачанный файл. Если Android спросит — разреши установку из браузера.",
   "Искра познакомится с тобой за четыре коротких шага. Каждое разрешение можно пропустить."],
  faq="Вопросы", qa=[("Это правда бесплатно?","Да. Команды, память, заметки, напоминания и погода ничего не стоят. Бесплатный «мозг» OpenRouter — примерно 50 сообщений в день; платные сервисы — доли копейки за ответ, с дневными лимитами, которые задаёшь ты."),
   ("Безопасно ли ставить APK?","Код открыт, каждую сборку делает GitHub Actions из открытого репозитория, APK подписан ключом проекта. Рядом лежит контрольная сумма .sha256."),
   ("Какие данные уходят с телефона?",f'Только то, что нужно функции, которой ты пользуешься: сообщение — выбранной модели ИИ, текст ответа — облачному голосу, город — для погоды. Полная таблица — в <a href="{REPO}/blob/main/PRIVACY.md#русский">политике конфиденциальности</a>.'),
   ("Она человек?","Нет — Искра программа с ИИ и честно скажет об этом, если спросить.")],
  foot=f'Искра — свободная программа по GNU GPL v3. Рисунки персонажа © владелец проекта. Погода: <a href="https://open-meteo.com">Open-Meteo</a>. · <a href="{REPO}">GitHub</a> · <a href="{REPO}/blob/main/PRIVACY.md#русский">Конфиденциальность</a> · <a href="{REPO}/issues">Сообщить о проблеме</a>'),
}
for code, t in T.items():
    cards = "".join(f'<div class="card"><img src="{t["img"]}{i}.webp" alt="" loading="lazy"><h3>{h}</h3><p>{p}</p></div>' for i, h, p in t["cards"])
    steps = "".join(f"<li>{s}</li>" for s in t["steps"])
    qa = "".join(f"<details><summary>{q}</summary><p>{a}</p></details>" for q, a in t["qa"])
    html = f"""<!doctype html>
<html lang="{t['lang']}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{t['title']}</title><meta name="description" content="{t['desc']}">
<meta property="og:title" content="{t['title']}"><meta property="og:description" content="{t['desc']}">
<meta property="og:image" content="https://expireduser-source.github.io/Iskra/img/icon.png"><link rel="icon" href="{t['img']}icon.png">
<style>{CSS}</style></head><body><div class="wrap">
<nav><b>✨ {'Iskra' if code == 'en' else 'Искра'}</b><span class="links"><a href="{REPO}/releases/latest">{'Releases' if code == 'en' else 'Версии'}</a><a href="{t['other'][1]}">{t['other'][0]}</a></span></nav>
<div class="hero"><div><h1>{t['h1']}</h1><p>{t['lead']}</p>
<a class="btn" href="{APK}">{t['dl']}</a><a class="btn ghost" href="{REPO}">{t['src']}</a>
<p class="small" style="margin-top:12px">{t['note']}</p>
<div class="qr"><img src="{t['img']}qr-download.svg" alt="QR"><span class="small">{t['qr']}</span></div></div>
<img class="char" src="{t['img']}iskra-demo.webp" alt="{'Iskra waves, walks, jumps and wags her tail' if code == 'en' else 'Искра машет, идёт, прыгает и виляет хвостом'}"></div>
<section><h2>{t['feat']}</h2><div class="grid">{cards}</div></section>
<section><h2>{t['how']}</h2><ol class="steps">{steps}</ol></section>
<section><h2>{t['faq']}</h2>{qa}</section>
<footer>{t['foot']}</footer></div></body></html>
"""
    out = pathlib.Path("docs/index.html" if code == "en" else "docs/ru/index.html")
    out.write_text(html, encoding="utf-8")
print("ok")
