
import fs from 'fs';
import path from 'path';

/**
 * POKER DATASET PROCESSOR (Developer Studio Tool)
 * 
 * Logic:
 * 1. Takes an image.
 * 2. Uses Gemini to identify cards (hole and community).
 * 3. Renames the file to comm_XXXX_hole_YYYY.ext
 * 4. Deletes if cards aren't clear.
 */

const API_KEY = "AIzaSyCs53i6-yYHYEJ1mx06pVaNQbKl-3bIlxw";
const TARGET_DIR = process.argv[2];

if (!API_KEY) {
    console.error("Error: API_KEY is missing.");
    process.exit(1);
}

if (!TARGET_DIR || !fs.existsSync(TARGET_DIR)) {
    console.error("Error: Please provide a valid target directory path.");
    process.exit(1);
}

const MODEL = 'gemini-2.5-flash';

async function identifyCards(imagePath) {
    const imageData = fs.readFileSync(imagePath).toString('base64');
    
    const prompt = `
        Identify poker cards in this image. 
        Return ONLY a JSON object:
        {
            "hole": "2hKd", 
            "comm": "3s5c9h",
            "quality": "good" | "poor"
        }
        
        Rules:
        - hole: the two player cards at the bottom.
        - comm: the 3 to 5 community cards in the middle.
        - cards in lowercase using h, d, c, s (hearts, diamonds, clubs, spades).
        - if cards are blurry or missing, set quality to 'poor'.
        - return empty string for cards if not found.
    `;

    const url = `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent?key=${API_KEY}`;
    
    const body = {
        contents: [{
            parts: [
                { text: prompt },
                { inlineData: { mimeType: "image/jpeg", data: imageData } }
            ]
        }],
        generationConfig: {
            responseMimeType: "application/json"
        }
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
            console.error(`  [ERROR] API Response: ${JSON.stringify(json)}`);
        }
    } catch (e) {
        console.error(`  [ERROR] Fetch for ${imagePath}:`, e.message);
    }
    return null;
}

async function run() {
    const allFiles = fs.readdirSync(TARGET_DIR);
    const files = allFiles.filter(f => f.match(/\.(jpg|jpeg|png)$/i));
    
    console.log(`Scanning ${files.length} files in ${TARGET_DIR}...`);

    for (const file of files) {
        const filePath = path.join(TARGET_DIR, file);
        console.log(`\nProcessing: ${file}`);
        
        // Add delay for free tier RPM limit (approx 12 seconds to be safe)
        await new Promise(r => setTimeout(r, 12000));

        const result = await identifyCards(filePath);
        if (!result) {
            console.log("  [SKIP] API failure.");
            continue;
        }

        if (result.quality === 'poor' || (result.hole === '' && result.comm === '')) {
            console.log(`  [DELETE] Poor quality or no cards identified.`);
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
    console.log("\nWork complete.");
}

run();
