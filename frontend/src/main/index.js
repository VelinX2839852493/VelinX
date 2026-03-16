// main.js - Electron application main-process entry

const {app, dialog} = require('electron');
const path = require('path');
const fs = require('fs');
const fsp = require('fs/promises');
const https = require('https');

const {readConfig} = require('./utils/pathUtils');
const BaseHandler = require('./bot/BaseHandler');
const ModelWindow = require('./windows/model-ui');
const DragHandler = require('./ipc/draghandler');
const MenuHandler = require('./ipc/menuhandler');
const ChangeRoleUi = require('./windows/change-live2d-role-ui');

const config = readConfig();
void config;

BaseHandler.init();
ChangeRoleUi.initIPC();
DragHandler.init();
MenuHandler.init();

const CUBISM_CORE_URL =
    'https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js';
const CUBISM_CORE_PATH = path.join(
    __dirname,
    '..',
    'renderer',
    'assets',
    'vendor',
    'live2dcubismcore.min.js'
);

let quitCleanupStarted = false;

function downloadFile(url, dest, timeoutMs = 15000) {
    return new Promise((resolve, reject) => {
        const tempDest = `${dest}.tmp`;
        const file = fs.createWriteStream(tempDest);
        let settled = false;

        const fail = (err) => {
            if (settled) return;
            settled = true;
            file.close(() => {
                fs.rm(tempDest, {force: true}, () => reject(err));
            });
        };

        const req = https.get(url, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                const nextUrl = new URL(res.headers.location, url).toString();
                res.resume();
                file.close(() => {
                    fs.rm(tempDest, {force: true}, () => {
                        downloadFile(nextUrl, dest, timeoutMs).then(resolve).catch(reject);
                    });
                });
                return;
            }

            if (res.statusCode !== 200) {
                res.resume();
                fail(new Error(`Download failed, HTTP ${res.statusCode}`));
                return;
            }

            res.pipe(file);
        });

        req.setTimeout(timeoutMs, () => {
            req.destroy(new Error('Download timed out'));
        });

        req.on('error', fail);
        file.on('error', fail);

        file.on('finish', () => {
            file.close((closeErr) => {
                if (closeErr) {
                    fail(closeErr);
                    return;
                }
                fs.rename(tempDest, dest, (renameErr) => {
                    if (renameErr) {
                        fail(renameErr);
                        return;
                    }
                    if (settled) return;
                    settled = true;
                    resolve();
                });
            });
        });
    });
}

async function ensureCubismCore() {
    if (fs.existsSync(CUBISM_CORE_PATH)) return;
    await fsp.mkdir(path.dirname(CUBISM_CORE_PATH), {recursive: true});
    await downloadFile(CUBISM_CORE_URL, CUBISM_CORE_PATH);
}

app.whenReady().then(async () => {
    try {
        await ensureCubismCore();
    } catch (error) {
        if (!fs.existsSync(CUBISM_CORE_PATH)) {
            dialog.showErrorBox(
                'Startup failed',
                `The first startup needs to download Live2D Core.\nError: ${error.message}`
            );
            app.quit();
            return;
        }
    }

    ModelWindow.create();

    setTimeout(() => {
        // Mouse tracking starts automatically after the window finishes loading.
    }, 1000);
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

app.on('activate', () => {
    if (ModelWindow.get() === null) {
        ModelWindow.create();
    }
});

app.on('will-quit', (event) => {
    if (quitCleanupStarted) {
        return;
    }

    event.preventDefault();
    quitCleanupStarted = true;

    console.log('Application is quitting, waiting for backend cleanup...');

    void BaseHandler.shutdownForAppQuit()
        .catch((error) => {
            console.error('Failed to shut down the backend before app quit:', error);
            BaseHandler.destroy();
        })
        .finally(() => {
            app.quit();
        });
});
