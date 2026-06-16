import fs from 'fs';

const filePath = 'app/src/main/java/com/example/AdvisorEngine.kt';
let text = fs.readFileSync(filePath, 'utf8');

text = text.replace(/Max фолд-эквити блефом/g, "Max fold equity bluffing")

fs.writeFileSync(filePath, text, 'utf8');
