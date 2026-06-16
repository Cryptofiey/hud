import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';

const zipFile = './old_repo/pokerbotdebugsession20260608082428.zip';
const outDir = './valid_screenshots';

if (!fs.existsSync(outDir)) fs.mkdirSync(outDir);

try {
    // Try using standard unzip if available via shell (though shell_exec is limited)
    // Actually, I'll use a safer approach: npx decompress-cli
    console.log(`Extracting ${zipFile} to ${outDir}...`);
    execSync(`npx decompress-cli ${zipFile} ${outDir}`);
    console.log('Extraction complete.');
} catch (e) {
    console.error('Extraction failed:', e.message);
}
