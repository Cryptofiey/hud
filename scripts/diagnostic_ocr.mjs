// Diagnostic logic for OCR failure isolation
import fs from 'fs';
import path from 'path';

// This script will serve as the diagnostic script requested by the user.
// Since Tesseract/Canvas might be heavy, we will output an HTML file to visualize coordinate ranges.
// It creates a visualization of the coordinate ranges over the provided screenshot image.

const screenshotPath = '../downloaded_logs/screenshot_1.jpg';
let screenshotBase64 = '';
try {
  screenshotBase64 = fs.readFileSync(path.resolve(__dirname, screenshotPath), 'base64');
} catch (e) {
  console.log("Could not find screenshot_1.jpg, using placeholder");
}

const htmlContent = `
<!DOCTYPE html>
<html>
<head>
    <title>OCR Diagnostic Viewer</title>
    <style>
        body { font-family: sans-serif; background: #222; color: #fff; margin: 0; padding: 20px; }
        .container { position: relative; display: inline-block; }
        img { max-width: 800px; display: block; }
        .box { position: absolute; border: 2px solid; color: white; padding: 2px; font-size: 10px; font-weight: bold; text-shadow: 1px 1px 0 #000; box-sizing: border-box; }
        .hero { border-color: #00ff00; background: rgba(0, 255, 0, 0.2); }
        .comm { border-color: #ffff00; background: rgba(255, 255, 0, 0.2); }
        .header { border-color: #ff0000; background: rgba(255, 0, 0, 0.2); }
        .opp-search { border-color: #0088ff; background: rgba(0, 136, 255, 0.1); }
        h1, p { margin: 10px 0; }
    </style>
</head>
<body>
    <h1>Diagnostic Viewer for 6-8 Max Tables</h1>
    <p>Visualizing Exclusion Zones vs Search Zones (Based on OpponentScanner.kt limits)</p>
    
    <div class="container">
        <!-- Assuming base64 image or normal path -->
        <img id="screenshot" src="data:image/jpeg;base64,${screenshotBase64}" >
        
        <!-- Script to dynamically generate boxes based on image dimensions -->
        <div id="overlays"></div>
    </div>

    <script>
        window.onload = () => {
            const img = document.getElementById('screenshot');
            const overlays = document.getElementById('overlays');
            
            // Wait for image load if not base64
            setTimeout(() => {
                const w = img.clientWidth;
                const h = img.clientHeight;
                
                function drawBox(className, text, leftPct, topPct, widthPct, heightPct) {
                    const div = document.createElement('div');
                    div.className = 'box ' + className;
                    div.style.left = (leftPct * w) + 'px';
                    div.style.top = (topPct * h) + 'px';
                    div.style.width = (widthPct * w) + 'px';
                    div.style.height = (heightPct * h) + 'px';
                    div.textContent = text;
                    overlays.appendChild(div);
                }

                // 1. inTopHeader (y < 0.09) - Excluded
                drawBox('header', 'Top Header (< 9%)', 0, 0, 1, 0.09);
                
                // 2. inCommunityCards (x: 25-75%, y: 38-68%) - Excluded
                drawBox('comm', 'Community Cards (25-75%, 38-68%)', 0.25, 0.38, 0.50, 0.30);
                
                // 3. inHeroCards (x: 53-95%, y: 68-98%) - Excluded
                drawBox('hero', 'Hero Cards (53-95%, 68-98%)', 0.53, 0.68, 0.42, 0.30);

                // 4. Button extraction (bottom 15%)
                drawBox('header', 'Buttons Zone (> 85%)', 0, 0.85, 1, 0.15);
                
                // 5. Hero Stack Search (x: 35-65%, y: > 72%)
                drawBox('hero', 'Hero Stats Search', 0.35, 0.72, 0.30, 0.28);
            }, 100);
        }
    </script>
</body>
</html>
`;

fs.writeFileSync(path.resolve(__dirname, '../diagnostic_viewer.html'), htmlContent);
console.log('Diagnostic viewer generated at diagnostic_viewer.html');
