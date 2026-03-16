// changerole.js
const {dialog, BrowserWindow} = require('electron');
const fs = require('fs');
const path = require('path');

/**
 * 校验模型文件的有效性 (支持 Live2D 和 Spine)
 * @param {string} modelPath 模型文件的完整路径
 * @returns {boolean} 是否有效
 */
function validateModelFile(modelPath) {
    try {
        const folderPath = path.dirname(modelPath);

        // --- 情况 A: 处理 Spine 二进制文件 (.skel) ---
        // 二进制文件不能用 JSON.parse，通常只要文件存在且非空即可
        if (modelPath.endsWith('.skel')) {
            const stats = fs.statSync(modelPath);
            if (stats.size === 0) throw new Error('Spine .skel 文件为空');
            return true; // 暂不做深度的二进制头校验
        }

        // --- 情况 B: 处理 JSON 文件 (.json / .model3.json) ---
        const content = fs.readFileSync(modelPath, 'utf-8');
        let config;
        try {
            config = JSON.parse(content);
        } catch (e) {
            throw new Error('JSON 解析失败，文件格式错误');
        }

        // 1. 判断是否为 Live2D (.model3.json)
        // 特征：通常包含 Version 且 >= 3
        if (modelPath.endsWith('.model3.json') || (config.Version && config.FileReferences)) {
            if (!config.Version || config.Version < 3) {
                throw new Error('Live2D: 检测到不支持的旧版本或非标准配置文件');
            }
            // 检查核心 Moc 文件
            if (config.FileReferences && config.FileReferences.Moc) {
                const mocPath = path.join(folderPath, config.FileReferences.Moc);
                if (!fs.existsSync(mocPath)) {
                    throw new Error(`Live2D: 缺少核心文件 ${config.FileReferences.Moc}`);
                }
            }
            return true;
        }

        // 2. 判断是否为 Spine JSON
        // 特征：通常包含 "skeleton" 对象
        if (config.skeleton || (config.bones && config.slots)) {
            // 这里是 Spine 的 JSON
            // 可以在这里加额外的校验，比如检查 .atlas 文件是否存在
            // 大多数 Spine 项目会配套一个 .atlas 文件
            const atlasFiles = fs.readdirSync(folderPath).filter(f => f.endsWith('.atlas') || f.endsWith('.atlas.txt'));
            if (atlasFiles.length === 0) {
                console.warn('警告:在该文件夹中未找到配套的 .atlas 文件，模型可能无法正确加载贴图。');
                // 注意：这里可以选择 throw Error 也可以仅警告，视你的加载器容错率而定
            }
            return true;
        }

        // 既不是 Live2D 也不是 Spine
        throw new Error('无法识别的模型格式 (未找到 Live2D Version 或 Spine skeleton 字段)');

    } catch (error) {
        dialog.showErrorBox('模型校验失败', `该模型文件看似损坏或格式不正确。\n文件: ${path.basename(modelPath)}\n原因: ${error.message}`);
        return false;
    }
}

/**
 * 选择Live2D模型文件夹并查找.model3.json文件
 */
async function selectModelFolder() {
    // 1. 修改对话框标题，体现支持两种格式
    const dialogResult = await dialog.showOpenDialog({
        title: '选择 Live2D 或 Spine 模型文件夹',
        properties: ['openDirectory'],
        parent: BrowserWindow.getFocusedWindow()
    });

    if (dialogResult.canceled) {
        return null;
    }

    const selectedFolder = dialogResult.filePaths[0];
    let modelJsonPath = null;

    try {
        const files = fs.readdirSync(selectedFolder);

        // === 核心修改：扩展查找逻辑 ===

        // 优先级 1: 查找 Live2D 模型 (.model3.json)
        const live2dFile = files.find(file => file.endsWith('.model3.json'));

        // 优先级 2: 查找 Spine 二进制模型 (.skel)
        const spineSkelFile = files.find(file => file.endsWith('.skel'));

        // 优先级 3: 查找 Spine JSON 模型 (.json)，但需排除 .model3.json
        // (注：Spine的json通常比较通用，如果文件夹里有 package.json 可能会误判，
        // 建议结合 validateModelFile 进行内容校验)
        const spineJsonFile = files.find(file => file.endsWith('.json') && !file.endsWith('.model3.json') && !file.endsWith('model0.json'));

        let potentialPath = null;

        if (live2dFile) {
            potentialPath = path.join(selectedFolder, live2dFile);
        } else if (spineSkelFile) {
            potentialPath = path.join(selectedFolder, spineSkelFile);
        } else if (spineJsonFile) {
            potentialPath = path.join(selectedFolder, spineJsonFile);
        }
        // 如果三个都没找到
        if (!potentialPath) {
            dialog.showErrorBox('选择失败', '未找到 Live2D (.model3.json) 或 Spine (.skel/.json) 模型文件');
            return null;
        }

        // === 校验逻辑 ===
        // 注意：你的 validateModelFile 函数也需要更新，
        // 以便它能识别并验证 Spine 文件的格式，而不仅仅是 Live2D。
        if (validateModelFile(potentialPath)) {
            modelJsonPath = potentialPath;
        } else {
            // 校验失败（例如找到了 json 但不是合法的模型文件）
            // 可以在 validateModelFile 内部打印更具体的错误日志
            return null;
        }

    } catch (err) {
        dialog.showErrorBox('读取失败', err.message);
        return null;
    }

    return modelJsonPath;
}

module.exports = selectModelFolder;