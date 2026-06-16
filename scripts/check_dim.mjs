import { Jimp } from 'jimp';

async function check() {
    const file = 'processed_screenshots/comm_10dqcqh3s_hole_.jpg';
    const img = await Jimp.read(file);
    console.log(`Dimensions of ${file}: ${img.bitmap.width}x${img.bitmap.height}`);
}

check();
