import { $ } from 'zx';

async function run() {
    try {
        await $`curl -sSL https://bootstrap.pypa.io/get-pip.py -o get-pip.py`
        await $`python3 get-pip.py`
        await $`python3 -m pip install gdown`
        await $`python3 -m gdown --folder 1PnDobWj3RBd0LXmGiKEECAKP_WBdDvmR -O downloaded_screens`
        await $`ls -la downloaded_screens`
    } catch (e) {
        console.error(e)
    }
}
run();
