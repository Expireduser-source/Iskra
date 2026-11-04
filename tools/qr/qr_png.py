#!/usr/bin/env python3
"""QR code of the project page for «Рассказать другу»: app/src/main/res/drawable-nodpi/qr_project.png.

    pip install qrcode pillow && python3 -I tools/qr/qr_png.py
Decode the result before committing (any phone camera, or OpenCV's QRCodeDetector) — it must give the URL exactly.
"""
import qrcode

URL = "https://github.com/Expireduser-source/Iskra"
q = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4, box_size=12)
q.add_data(URL)
q.make(fit=True)
q.make_image(fill_color="black", back_color="white").convert("L").save("app/src/main/res/drawable-nodpi/qr_project.png", optimize=True)
print("ok", q.version)
