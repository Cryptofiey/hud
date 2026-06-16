
import fs from 'fs';
import path from 'path';

/**
 * POKER DATASET PROCESSOR (Developer Studio Tool)
 * 
 * Usage: 
 * 1. Set GEMINI_API_KEY in environment.
 * 2. Run: node scripts/process_dataset.mjs <directory_path>
 */

const API_KEY = process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY;
const TARGET_DIR = process.argv[2];

if (!API_KEY) {
    console.error("Error: GEMINI_API_KEY or GOOGLE_API_KEY environment variable is not set.");
    process.exit(1);
}

if (!TARGET_DIR || !fs.existsSync(TARGET_DIR)) {
    console.error("Error: Please provide a valid target directory path.");
    process.exit(1);
}

const MODEL = 'gemini-flash-latest'; 

async function identifyCards(imagePath) {
    const imageData = fs.readFileSync(imagePath).toString('base64');
    
    // Add a significant delay between requests to avoid rate limits on free tier
    await new Promise(resolve => setTimeout(resolve, 15000));
    
    const prompt = `
        Identify poker cards in this image. 
        Return ONLY a JSON object:
        {
            "hole": "2hKd", 
            "comm": "3s5c9h",
            "quality": "good" | "poor"
        }
    `;

    const url = `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent?key=${API_KEY}`;
    
    const body = {
        contents: [{
            parts: [
                { text: prompt },
                { inlineData: { mimeType: "image/jpeg", data: imageData } }
            ]
        }]
    };

    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        
        const json = await response.json();
        if (json.candidates && json.candidates[0].content.parts[0].text) {
            return JSON.parse(json.candidates[0].content.parts[0].text);
        } else {
            console.error(`  [ERROR] API Response mismatch: ${JSON.stringify(json)}`);
        }
    } catch (e) {
        console.error(`  [ERROR] Fetch for ${imagePath}:`, e.message);
    }
    return null;
}

async function run() {
    const files = fs.readdirSync(TARGET_DIR).filter(f => f.match(/\.(jpg|jpeg|png)$/i)).slice(0, 2);
    console.log(`Scanning ${files.length} files in ${TARGET_DIR} (Limited for test)...`);

    for (const file of files) {

        const filePath = path.join(TARGET_DIR, file);
        console.log(`\nProcessing: ${file}`);
        
        const result = await identifyCards(filePath);
        if (!result) {
            console.log("  [SKIP] API failure.");
            continue;
        }

        if (result.quality === 'poor' || (result.hole === '' && result.comm === '')) {
            console.log(`  [DELETE] Poor quality or no cards: ${result.reason || 'Not detected'}`);
            fs.unlinkSync(filePath);
        } else {
            const ext = path.extname(file);
            const newName = `comm_${result.comm}_hole_${result.hole}${ext}`;
            const newPath = path.join(TARGET_DIR, newName);
            
            if (newName === file) {
                console.log("  [KEEP] Name already correct.");
            } else {
                console.log(`  [RENAME] -> ${newName}`);
                fs.renameSync(filePath, newPath);
            }
        }
    }
    console.log("\nDataset processing complete.");
}

run();
