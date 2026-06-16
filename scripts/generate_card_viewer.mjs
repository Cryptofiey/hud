import fs from 'fs';
import path from 'path';

const dataDir = './downloaded_screens';
const htmlOutputPath = './diagnostic_viewer.html';

const files = fs.readdirSync(dataDir).filter(f => f.endsWith('.jpg') || f.endsWith('.png')).slice(0, 10);

let htmlContent = `
<!DOCTYPE html>
<html>
<head>
    <title>Card Scanner Diagnostic Viewer</title>
    <style>
        body { font-family: sans-serif; background: #222; color: #fff; margin: 0; padding: 20px; }
        .img-container { position: relative; display: inline-block; margin-bottom: 40px; margin-right: 20px; }
        img { width: 400px; display: block; } /* Fixed viewing width to fit multiple on screen */
        .box { position: absolute; border: 2px solid; color: white; padding: 2px; font-size: 10px; font-weight: bold; text-shadow: 1px 1px 0 #000; box-sizing: border-box; }
        .comm { border-color: #00ff00; background: rgba(0, 255, 0, 0.2); }
        .hole { border-color: #ff00ff; background: rgba(255, 0, 255, 0.2); }
        h1, p { margin: 10px 0; }
    </style>
</head>
<body>
    <h1>Card Scanner Bounding Box Diagnostics</h1>
    <p>We are mapping the exact HUD percentages over the <b>downloaded_screens</b> raw screenshots to ensure they line up perfectly before running pixel OCR matching.</p>
    <div>
`;

for (const f of files) {
    const imgPath = path.join(dataDir, f);
    const base64 = fs.readFileSync(imgPath, 'base64');
    
    htmlContent += `
        <div class="img-container">
            <h4>${f}</h4>
            <img src="data:image/jpeg;base64,${base64}" onload="drawBoxes(this)">
        </div>
    `;
}

htmlContent += `
    </div>
    <script>
        function drawBoxes(img) {
            const container = img.parentElement;
            const w = img.clientWidth;
            const h = img.clientHeight;
            
            // Expected scanner boundaries as defined in eval_templates.mjs
            // Community: x=0.10, y=0.40, w=0.80, h=0.14
            // Hole: x=0.35, y=0.65, w=0.35, h=0.14

            function drawBox(className, text, leftPct, topPct, widthPct, heightPct) {
                const div = document.createElement('div');
                div.className = 'box ' + className;
                div.style.left = (leftPct * w) + 'px';
                div.style.top = (topPct * h) + 'px';
                div.style.width = (widthPct * w) + 'px';
                div.style.height = (heightPct * h) + 'px';
                div.textContent = text;
                container.appendChild(div);
            }

            // Adjust these to match exactly where the player expects the scanner frames to be!
            drawBox('comm', 'Community Cards Frame', 0.10, 0.40, 0.80, 0.14);
            drawBox('hole', 'Hole Cards Frame', 0.35, 0.65, 0.35, 0.14);
        }
    </script>
</body>
</html>
`;

fs.writeFileSync(htmlOutputPath, htmlContent);
console.log('Diagnostic viewer generated at diagnostic_viewer.html');
console.log('Please open diagnostic_viewer.html to verify if the Community & Hole frames map correctly to the screenshots.');
