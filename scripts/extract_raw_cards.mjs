import fs from 'fs';
import path from 'path';
import { Jimp } from 'jimp';

/**
 * SCRIPT: scripts/extract_raw_cards.mjs
 * 
 * This script crops raw screenshots into individual card slots (5 for community, 2 for hole)
 * based on the validated coordinate frames. These "raw slices" are saved in 'extracted_raw_cards'.
 * 
 * The user can then select the clear ones, name them (e.g., 'Kh.png'), and move them to 'templates_pool'
 * to feed the 'eval_templates.mjs' OCR engine.
 * 
 * Run with: npx zx scripts/extract_raw_cards.mjs
 */

const inputDir = './downloaded_screens';
const outputDir = './extracted_raw_cards';

// Boundaries from eval_templates.mjs (validated by player as "perfectly aligned")
const COORDS = {
    comm: { x: 0.10, y: 0.40, w: 0.80, h: 0.14, slots: 5 },
    hole: { x: 0.35, y: 0.65, w: 0.35, h: 0.14, slots: 2 }
};

if (!fs.existsSync(outputDir)) {
    fs.mkdirSync(outputDir, { recursive: true });
}

async function extract() {
    console.log(`Starting extraction from: ${inputDir}`);
    
    if (!fs.existsSync(inputDir)) {
        console.error(`❌ Input directory ${inputDir} does not exist!`);
        return;
    }

    const files = fs.readdirSync(inputDir).filter(f => f.toLowerCase().endsWith('.jpg') || f.toLowerCase().endsWith('.png'));
    
    if (files.length === 0) {
        console.error(`❌ No screenshots found in ${inputDir}`);
        return;
    }

    console.log(`Found ${files.length} screenshots. This may take a moment...`);

    for (const f of files) {
        try {
            const imgPath = path.join(inputDir, f);
            const name = path.basename(f, path.extname(f));
            const img = await Jimp.read(imgPath);
            const { width: w, height: h } = img.bitmap;

            const subFolder = path.join(outputDir, name);
            if (!fs.existsSync(subFolder)) fs.mkdirSync(subFolder);

            // Extract Community Slots (5)
            const cX = Math.floor(w * COORDS.comm.x);
            const cY = Math.floor(h * COORDS.comm.y);
            const cW = Math.floor(w * COORDS.comm.w);
            const cH = Math.floor(h * COORDS.comm.h);
            const cSlotW = Math.floor(cW / COORDS.comm.slots);

            for (let i = 0; i < COORDS.comm.slots; i++) {
                const card = img.clone().crop({
                    x: cX + (i * cSlotW),
                    y: cY,
                    w: cSlotW,
                    h: cH
                });
                await card.write(path.join(subFolder, `comm_${i}.png`));
            }

            // Extract Hole Slots (2)
            const hX = Math.floor(w * COORDS.hole.x);
            const hY = Math.floor(h * COORDS.hole.y);
            const hW = Math.floor(w * COORDS.hole.w);
            const hH = Math.floor(h * COORDS.hole.h);
            const hSlotW = Math.floor(hW / COORDS.hole.slots);

            for (let i = 0; i < COORDS.hole.slots; i++) {
                const card = img.clone().crop({
                    x: hX + (i * hSlotW),
                    y: hY,
                    w: hSlotW,
                    h: hH
                });
                await card.write(path.join(subFolder, `hole_${i}.png`));
            }

            console.log(`✅ Extracted: ${f}`);
        } catch (err) {
            console.error(`❌ Error processing ${f}:`, err.message);
        }
    }

    console.log(`\n🎉 Done! All card slices are in: ${outputDir}`);
    console.log(`You can now download the ${outputDir} folder, pick the best templates, name them, and put them into 'templates_pool'.`);
}

extract().catch(console.error);
