import fs from 'fs';
import path from 'path';

// Let's print out what files we have
const dir = './processed_screenshots';
const files = fs.readdirSync(dir).filter(f => f.endsWith('.jpg') || f.endsWith('.png'));
console.log(`Found ${files.length} screenshots.`);
console.dir(files.slice(0, 10));
