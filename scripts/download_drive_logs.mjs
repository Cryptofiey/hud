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
    // Скачивание через curl с обработкой больших файлов
    await $`curl -L -c /tmp/cookies.txt -s "https://drive.google.com/uc?export=download&id=${fileId}" > /dev/null`
    let output = await $`curl -sL -b /tmp/cookies.txt "https://drive.google.com/uc?export=download&id=${fileId}" | grep -oP 'confirm=\\K[^&]+' || echo ""`
    let confirm = output.stdout.trim()
    let dlUrl = confirm ? `https://drive.google.com/uc?export=download&confirm=${confirm}&id=${fileId}` : `https://drive.google.com/uc?export=download&id=${fileId}`

    await $`curl -L -b /tmp/cookies.txt -o logs.zip "${dlUrl}"`
    await $`rm -f /tmp/cookies.txt`

    console.log('Unzipping logs.zip ...');
    await $`npx -y decompress-cli logs.zip downloaded_logs`
    console.log('✅ Logs downloaded and unzipped to downloaded_logs/ directory.');
} catch (e) {
    console.log('Error downloading or unzipping logs. Try downloading directly or checking the link.');
    console.error(e);
}
