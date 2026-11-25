# Pro promo video

Source of `app/src/main/res/raw/pro_promo.mp4`, the silent looping clip at the
top of the Pro page. An Android variant of the iOS promo in
`Marketing/ProPromo/` (10-bit HDR instead of Apple Log; no two-lens or transcript scenes).

```
npm i puppeteer-core            # once, anywhere
node render.mjs frames          # renders frames/ from promo.html (needs Google Chrome)
ffmpeg -framerate 30 -i frames/f%04d.png -c:v libx264 -preset slow -crf 25 \
  -pix_fmt yuv420p -movflags +faststart -an ProPromo.mp4
```
Copy the result over `pro_promo.mp4`. No audio track on purpose: it must never
interrupt whatever the person is listening to.
