from pathlib import Path
import base64

ROOT = Path(__file__).resolve().parent

def uri(path):
    return 'data:image/png;base64,' + base64.b64encode(path.read_bytes()).decode()

def crop(name, box, alt):
    x, y, w, h = box
    data = uri(ROOT / 'raw' / name)
    return f'<svg class="capture" viewBox="{x} {y} {w} {h}" role="img" aria-label="{alt}" xmlns="http://www.w3.org/2000/svg"><image href="{data}" width="1080" height="2400"/></svg>'

logo = uri(ROOT.parent.parent / 'branding' / 'tiaotiao-logo.png')
rules = crop('02-rules.png', (53, 290, 974, 1330), '真机规则列表：自动学习中的喜马拉雅样本和已验证的知乎方案')
status = crop('01-home.png', (53, 305, 974, 535), '真机首页：已选择应用、已学会和学习中的方案')
checks = crop('06-ai-checks.png', (53, 1333, 974, 501), '真机处理进度：保存目标、通过自动检查和启用规则')
edit = crop('04-edit-mode.png', (53, 628, 974, 1728), '真机编辑状态：100% 缩放、选点和保存按钮')
zoom = crop('05-edit-zoom.png', (53, 628, 974, 1728), '真机编辑状态：300% 缩放、目标位置和保存按钮')

html = '''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=1440, initial-scale=1">
<title>TiaoTiao · 社区项目介绍</title>
<style>
* { box-sizing: border-box; }
html { margin: 0; background: #fff; }
body { margin: 0; width: 1440px; padding: 64px 72px 58px; color: #202622; background: #fff; font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif; -webkit-font-smoothing: antialiased; }
header { padding-bottom: 40px; border-bottom: 2px solid #e6eae6; }
.brand { display: flex; align-items: center; gap: 22px; }
.logo { width: 76px; height: 76px; border-radius: 19px; }
h1 { margin: 0; font-size: 72px; line-height: 1.1; letter-spacing: -1.8px; font-weight: 650; }
.kind { margin-left: auto; color: #6c766f; font-size: 25px; letter-spacing: .4px; }
.intro { margin: 25px 0 0; font-size: 39px; line-height: 1.45; font-weight: 450; }
section { padding-top: 42px; }
.section-heading { display: flex; align-items: baseline; gap: 17px; }
.num { color: #276751; font: 500 27px/1.3 ui-monospace, SFMono-Regular, Menlo, monospace; }
h2 { margin: 0; font-size: 43px; line-height: 1.35; letter-spacing: -.6px; font-weight: 620; }
.description { margin: 13px 0 30px; color: #626b65; font-size: 28px; line-height: 1.5; }
.columns { display: grid; grid-template-columns: 1fr 1fr; gap: 38px; align-items: start; }
figure { margin: 0; min-width: 0; }
.frame { overflow: hidden; border: 1.5px solid #e3e8e1; background: #f7f8f3; border-radius: 18px; line-height: 0; }
.capture { width: 100%; height: auto; display: block; }
figcaption { color: #677168; font-size: 25px; line-height: 1.45; margin-top: 14px; }
.stack { display: flex; flex-direction: column; gap: 28px; }
.stack figcaption { margin: 0 0 12px; }
.note { font-size: 26px; line-height: 1.6; color: #526157; padding-left: 2px; margin: 1px 0 0; }
.edit-section { margin-top: 38px; border-top: 2px solid #e6eae6; }
footer { margin-top: 46px; border-top: 2px solid #e6eae6; padding-top: 30px; }
.footer-row { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; }
.repo { color: #276751; font: 500 29px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; white-space: nowrap; }
.tech { color: #66716a; font-size: 23px; white-space: nowrap; }
.small { margin: 13px 0 0; color: #798179; font-size: 23px; line-height: 1.65; }
</style></head><body>
<header>
  <div class="brand"><img class="logo" src="__LOGO__" alt="TiaoTiao"><h1>TiaoTiao</h1><span class="kind">开源项目 · 真机界面</span></div>
  <p class="intro">一个开源的 Android 开屏广告跳过工具</p>
</header>
<section>
  <div class="section-heading"><span class="num">01</span><h2>AI 自动识别</h2></div>
  <p class="description">识别关闭目标，自动尝试并检查结果。</p>
  <div class="columns">
    <figure><div class="frame">__RULES__</div><figcaption>识别后的方案与样本</figcaption></figure>
    <div class="stack">
      <figure><figcaption>首页状态</figcaption><div class="frame">__STATUS__</div></figure>
      <figure><figcaption>自动检查记录</figcaption><div class="frame">__CHECKS__</div></figure>
      <p class="note">通过验证的规则，可在本机复用。</p>
    </div>
  </div>
</section>
<section class="edit-section">
  <div class="section-heading"><span class="num">02</span><h2>位置可以自己改</h2></div>
  <p class="description">双击放大、拖动画面；选点后保存。</p>
  <div class="columns">
    <figure><div class="frame">__EDIT__</div><figcaption>100% · 在原图上选点</figcaption></figure>
    <figure><div class="frame">__ZOOM__</div><figcaption>300% · 放大查看关闭目标</figcaption></figure>
  </div>
</section>
<footer>
  <div class="footer-row"><span class="repo">github.com/csswork/TiaoTiao</span><span class="tech">Kotlin · Compose · 无障碍服务 · OCR</span></div>
  <p class="small">小米 14 Pro 真机截图，裁切排版。云端 AI 需自行配置 Key，项目仍在迭代。</p>
</footer>
</body></html>'''
for key, value in {'__LOGO__':logo, '__RULES__':rules, '__STATUS__':status, '__CHECKS__':checks, '__EDIT__':edit, '__ZOOM__':zoom}.items():
    html = html.replace(key,value)
output = ROOT / 'tiaotiao-community-v2.html'
output.write_text(html)
print(output)
