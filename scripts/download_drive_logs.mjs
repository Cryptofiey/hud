#!/usr/bin/env zx

let url = process.argv[3];
if (!url) {
  console.log('Provide GDrive url');
  process.exit(1);
}

let fileId = '';
const matchD = url.match(/\/d\/([a-zA-Z0-9_-]+)/);
const matchId = url.match(/id=([a-zA-Z0-9_-]+)/);
if (matchD) fileId = matchD[1];
else if (matchId) fileId = matchId[1];
else fileId = url;

console.log(`Downloading file ID: ${fileId}...`);

// Очистка старых логов
await $`rm -rf downloaded_logs logs.zip`

try {
    const userAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
    
    // First request to check for virus scan warnings (large files)
    let res = await fetch(`https://drive.google.com/uc?export=download&id=${fileId}`, {
        headers: { 'User-Agent': userAgent }
    });
    
<<<<<<< HEAD
=======
<<<<<<< HEAD
>>>>>>> origin/main
    let confirmToken = '';
    const textSnapshot = await res.clone().text().then(t => t.slice(0, 10000)).catch(() => '');
    const confirmMatch = textSnapshot.match(/confirm=([a-zA-Z0-9_-]+)/);
    if (confirmMatch) {
        confirmToken = confirmMatch[1];
        console.log(`Found GDrive virus scan confirmation token: ${confirmToken}`);
    }

    let downloadUrl = `https://drive.google.com/uc?export=download&id=${fileId}`;
    if (confirmToken) {
        downloadUrl += `&confirm=${confirmToken}`;
<<<<<<< HEAD
=======
=======
    let downloadUrl = `https://drive.google.com/uc?export=download&id=${fileId}`;
    const textSnapshot = await res.text();
    
    // Modern GDrive uses hidden inputs in a form for confirmation
    const confirmMatch = textSnapshot.match(/name="confirm" value="([a-zA-Z0-9_-]+)"/);
    const uuidMatch = textSnapshot.match(/name="uuid" value="([a-zA-Z0-9_-]+)"/);
    
    if (confirmMatch) {
        const confirmToken = confirmMatch[1];
        console.log(`Found GDrive virus scan confirmation token: ${confirmToken}`);
        downloadUrl = `https://drive.usercontent.google.com/download?id=${fileId}&export=download&confirm=${confirmToken}`;
        if (uuidMatch) {
            downloadUrl += `&uuid=${uuidMatch[1]}`;
        }
    } else {
        // Fallback for simple confirm=xxx links
        const confirmMatchLegacy = textSnapshot.match(/confirm=([a-zA-Z0-9_-]+)/);
        if (confirmMatchLegacy) {
            downloadUrl += `&confirm=${confirmMatchLegacy[1]}`;
        }
>>>>>>> origin/main
>>>>>>> origin/main
    }

    console.log('Downloading file raw buffer...');
    const finalRes = await fetch(downloadUrl, {
        headers: { 'User-Agent': userAgent }
    });
    
    if (!finalRes.ok) {
        throw new Error(`Failed to download file: ${finalRes.statusText} (${finalRes.status})`);
    }

    const arrayBuffer = await finalRes.arrayBuffer();
    const fs = require('fs');
    fs.writeFileSync('logs.zip', Buffer.from(arrayBuffer));
    console.log(`Successfully saved logs.zip (Size: ${fs.statSync('logs.zip').size} bytes).`);

    console.log('Unzipping logs.zip ...');
    await $`mkdir -p downloaded_logs`;
    await $`unzip -o logs.zip -d downloaded_logs`;
    console.log('✅ Logs downloaded and unzipped to downloaded_logs/ directory.');
} catch (e) {
    console.log('Error downloading or unzipping logs. Try checking the link or downloading directly.');
    console.error(e);
}
