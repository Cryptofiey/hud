import fs from 'fs';
import path from 'path';
import { Jimp } from 'jimp';

/**
 * SERVER DEBUGGER FOR PIXEL-BY-PIXEL CARD RECOGNITION.
 * 
 * Instructions:
 * 1. Place your prepared cropped & signed template cards in the 'templates_pool' folder.
 *    Name them like: "10h.png", "As.png", "2c.png", etc.
 * 2. This script will scan the full screenshots in 'processed_screenshots' folder
 *    by comparing pixels (MSE) in the predicted card zones against your templates.
 */

const dataDir = '../processed_screenshots';
const templatesDir = '../templates_pool'; 
const mseThreshold = 1500.0;

if (!fs.existsSync(templatesDir)) {
    fs.mkdirSync(templatesDir, { recursive: true });
}

// Load templates
async function loadTemplates() {
    const templates = [];
    const files = fs.readdirSync(templatesDir).filter(f => f.endsWith('.png'));
    for (const f of files) {
        const text = path.basename(f, '.png');
        const img = await Jimp.read(path.join(templatesDir, f));
        templates.push({ text, img });
    }
    return templates;
}

// Fast Mean Squared Error comparison like the Android TemplateManager
function computeMseFast(inputImg, startX, startY, templateImg) {
    let sumSq = 0;
    const tW = templateImg.bitmap.width;
    const tH = templateImg.bitmap.height;
    const maxW = inputImg.bitmap.width;
    const maxH = inputImg.bitmap.height;

    if (startX + tW > maxW || startY + tH > maxH) return Infinity;

    for (let ty = 0; ty < tH; ty++) {
        for (let tx = 0; tx < tW; tx++) {
            const hex1 = inputImg.getPixelColor(startX + tx, startY + ty);
            const hex2 = templateImg.getPixelColor(tx, ty);
            
            const r1 = (hex1 >> 24) & 255;
            const g1 = (hex1 >> 16) & 255;
            const b1 = (hex1 >> 8) & 255;
            const gray1 = (r1 + g1 + b1) / 3;
            
            const r2 = (hex2 >> 24) & 255;
            const g2 = (hex2 >> 16) & 255;
            const b2 = (hex2 >> 8) & 255;
            const gray2 = (r2 + g2 + b2) / 3;
            
            const diff = gray1 - gray2;
            sumSq += diff * diff;
        }
    }
    return sumSq / (tW * tH);
}

// Match multiple templates in a cropped zone
function matchMultiple(cropImg, templates, maxCards) {
    if (templates.length === 0) return [];
    
    const candidates = [];
    const inputW = cropImg.bitmap.width;
    const inputH = cropImg.bitmap.height;

    for (const tmpl of templates) {
        const tW = tmpl.img.bitmap.width;
        const tH = tmpl.img.bitmap.height;
        if (tW > inputW || tH > inputH) continue;

        const maxY = inputH - tH;
        const maxX = inputW - tW;
        const yStep = Math.max(1, Math.floor(maxY / 4));
        const xStep = 2;

        for (let x = 0; x <= maxX; x += xStep) {
            let bestMseLoc = Infinity;
            for (let y = 0; y <= maxY; y += yStep) {
                const mse = computeMseFast(cropImg, x, y, tmpl.img);
                if (mse < bestMseLoc) bestMseLoc = mse;
            }
            if (bestMseLoc < mseThreshold) {
                candidates.push({ x, text: tmpl.text, mse: bestMseLoc, tW, tH });
            }
        }
    }

    candidates.sort((a, b) => a.mse - b.mse);
    const accepted = [];
    for (const cand of candidates) {
        const spaceThreshold = cand.tW * 0.75;
        const overlaps = accepted.some(acc => Math.abs(acc.x - cand.x) < spaceThreshold);
        if (!overlaps) {
            accepted.push(cand);
        }
    }

    accepted.sort((a, b) => a.x - b.x);
    return accepted.slice(0, maxCards).map(c => c.text);
}

async function runTests() {
    console.log("Loading templates...");
    const templates = await loadTemplates();
    if (templates.length === 0) {
        console.log(`⚠️ No templates found in ${templatesDir} !`);
        console.log(`Please upload your cropped & named card templates there, then re-run this script.`);
        console.log(`Command to run: npx zx scripts/eval_templates.mjs`);
        return;
    }
    console.log(`Loaded ${templates.length} templates.`);

    const files = fs.readdirSync(dataDir).filter(f => f.endsWith('.jpg') || f.endsWith('.png'));
    console.log(`Found ${files.length} screenshots to test.`);

    let passed = 0;
    let failed = 0;

    for (const f of files) {
        const imgPath = path.join(dataDir, f);
        const name = path.basename(f, path.extname(f));
        const parts = name.split('_hole_');
        const expectedComm = parts[0].replace('comm_', '').toLowerCase();
        const expectedHole = (parts.length > 1 ? parts[1] : '').toLowerCase();

        const img = await Jimp.read(imgPath);
        const w = img.bitmap.width;
        const h = img.bitmap.height;

        // Bounding boxes just like the Android app HUD overlay
        const cLeft = Math.floor(w * 0.10);
        const cTop = Math.floor(h * 0.40);
        const cW = Math.floor(w * 0.80);
        const cH = Math.floor(h * 0.14);

        const hLeft = Math.floor(w * 0.35);
        const hTop = Math.floor(h * 0.65);
        const hW = Math.floor(w * 0.35);
        const hH = Math.floor(h * 0.14);

        const commCrop = img.clone().crop({ x: cLeft, y: cTop, w: cW, h: cH });
        const holeCrop = img.clone().crop({ x: hLeft, y: hTop, w: hW, h: hH });

        const detectedComm = matchMultiple(commCrop, templates, 5).join('').toLowerCase();
        const detectedHole = matchMultiple(holeCrop, templates, 2).join('').toLowerCase();

        let matchC = true;
        let matchH = true;
        
        // Simple string equivalence check, can be improved.
        for(let char of detectedComm) { if(!expectedComm.includes(char)) matchC = false; }
        for(let char of detectedHole) { if(!expectedHole.includes(char)) matchH = false; }

        if (detectedComm.length !== expectedComm.length) matchC = false;
        if (detectedHole.length !== expectedHole.length) matchH = false;

        if (matchC && matchH) {
            passed++;
            console.log(`✅ PASS: ${f}`);
        } else {
            failed++;
            console.log(`❌ FAIL: ${f}`);
            console.log(`   Expected Comm: ${expectedComm} | Detected: ${detectedComm}`);
            console.log(`   Expected Hole: ${expectedHole} | Detected: ${detectedHole}`);
        }
    }
    
    console.log(`\n=== TEST SUMMARY ===`);
    console.log(`Passed: ${passed}`);
    console.log(`Failed: ${failed}`);
    console.log(`Success Rate: ${((passed/(passed+failed))*100).toFixed(2)}%`);
}

runTests().catch(console.error);
