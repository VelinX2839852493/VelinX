//路径配置
const {app} = require('electron');
const path = require('path');
const fs = require('fs');
const {URL} = require('url');

// 导入默认配置模板
const defaultConfig = require('../../shared/default-config');

/**
 * 判断当前应用是否已打包
 * @returns {boolean} true 表示是安装后的环境，false 表示是 npm run dev 开发环境
 */
function getIsPackaged() {
    if (app) {
        return app.isPackaged;
    }
    // 如果 app 对象不可用，通过路径是否包含 asar 虚拟文件系统来判断
    return __dirname.includes('app.asar');
}

/** 获取项目根目录路径 */
function getProjectRoot() {
    return path.resolve(__dirname, '../../../');
}

/** 获取工作空间根目录（通常是项目根目录的上一级） */
function getWorkspaceRoot() {
    return path.resolve(getProjectRoot(), '..');
}

/**
 * 获取用户配置文件 config.json 的存放路径
 * 优先级：便携版目录 > 程序所在目录 > 项目开发目录
 */
function getUserConfigPath() {
    // 如果是便携版（Portable），配置存放在可执行文件同级目录
    if (process.env.PORTABLE_EXECUTABLE_DIR) {
        return path.join(process.env.PORTABLE_EXECUTABLE_DIR, 'config.json');
    }

    // 如果已打包，存放在 .exe/可执行文件 所在的文件夹下
    if (getIsPackaged()) {
        return path.join(path.dirname(app ? app.getPath('exe') : process.execPath), 'config.json');
    }

    // 开发环境：存放在项目根目录
    return path.join(getProjectRoot(), 'config.json');
}

/**
 * 获取启动 Python 后端服务的参数
 * @returns {object} 包含命令、参数、执行选项
 */
function getPythonBinaryPath() {
    const projectRoot = getProjectRoot();

    // 开发环境：使用 python 解释器运行脚本
    if (!getIsPackaged()) {
        return {
            command: 'python',
            args: ['-u', path.join(projectRoot, 'py_script/mainbot.py')],
            options: {
                cwd: path.join(projectRoot, 'py_script'),
                env: {
                    ...process.env,
                    PYTHONPATH: projectRoot,
                },
            },
        };
    }

    // 生产环境：直接运行打包好的 python 二进制文件 (main.exe)
    const binaryName = process.platform === 'win32' ? 'main.exe' : 'main';
    const exeDir = path.join(process.resourcesPath, 'backend', 'py_service', 'main');

    return {
        command: path.join(exeDir, binaryName),
        args: [],
        options: {
            cwd: exeDir,
            env: {...process.env},
        },
    };
}

/**
 * 获取启动 Java 后端服务的参数
 * @param {object} options 包含 baseUrl 等
 */
function getJavaBinaryPath(options = {}) {
    const javaExec = 'java';
    const baseUrl = typeof options.baseUrl === 'string' ? options.baseUrl : 'http://127.0.0.1:8081';
    const parsedUrl = new URL(baseUrl);
    const serverPort = parsedUrl.port || (parsedUrl.protocol === 'https:' ? '443' : '80');
    const serverArgs = [`--server.port=${serverPort}`]; // 动态指定端口

    // 开发环境：运行 target 下的 jar 包
    if (!getIsPackaged()) {
        const javaProjectRoot = path.join(getWorkspaceRoot(), 'java');
        const jarPath = path.join(javaProjectRoot, 'target', 'velia-1.0-SNAPSHOT.jar');

        return {
            command: javaExec,
            args: ['-jar', jarPath, ...serverArgs],
            options: {
                cwd: javaProjectRoot,
                env: {...process.env, JAVA_ROOT_DIR: javaProjectRoot},
            },
            jarPath,
        };
    }

    // 生产环境：运行 resources 目录下的 mainbot.jar
    const jarDir = path.join(process.resourcesPath, 'backend', 'java_service');
    const jarPath = path.join(jarDir, 'mainbot.jar');

    return {
        command: javaExec,
        args: ['-jar', jarPath, ...serverArgs],
        options: {
            cwd: jarDir,
            env: {...process.env, JAVA_ROOT_DIR: jarDir},
        },
        jarPath,
    };
}

/** 获取路径映射配置文件的位置 (path_mapping.json) */
function getPathMappingFilePath(options = {}) {
    const javaProcessConfig = getJavaBinaryPath(options);
    const javaBaseDir = javaProcessConfig?.options?.cwd || path.join(getWorkspaceRoot(), 'java');
    return path.join(javaBaseDir, 'path_mapping.json');
}

/** 读取路径映射文件内容 */
function readPathMapping(options = {}) {
    const mappingPath = getPathMappingFilePath(options);
    if (!fs.existsSync(mappingPath)) return {};

    try {
        const raw = fs.readFileSync(mappingPath, 'utf8');
        const parsed = JSON.parse(raw);
        return (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) ? parsed : {};
    } catch (error) {
        console.error('读取 path_mapping.json 失败:', error);
        return {};
    }
}

/** 根据 key 获取路径映射中的值 */
function getPathMappingValue(key, options = {}) {
    if (typeof key !== 'string' || key.trim() === '') return null;
    const pathMapping = readPathMapping(options);
    const value = pathMapping[key.trim()];
    return (value === undefined || value === null) ? null : String(value);
}

/** 获取 OpenAI 配置文件的位置 (openai_config.json) */
function getOpenAiConfigPath(options = {}) {
    const mappedPath = getPathMappingValue('OPENAI_CONFIG_PATH', options);
    if (mappedPath) {
        return mappedPath;
    }

    const javaProcessConfig = getJavaBinaryPath(options);
    const javaBaseDir = javaProcessConfig?.options?.cwd || path.join(getWorkspaceRoot(), 'java');
    return path.join(javaBaseDir, 'data', 'openai_config.json');
}

/**
 * 深度合并配置对象
 * 确保 backend 对象内的属性也能被正确合并，而不是被直接覆盖
 */
function mergeAppConfig(...configs) {
    return configs.reduce(
        (accumulator, current) => {
            if (!current || typeof current !== 'object') return accumulator;

            const nextConfig = {...accumulator, ...current};
            // 特殊处理 backend 字段，进行二级合并
            nextConfig.backend = {
                ...(accumulator.backend || {}),
                ...(current.backend || {}),
            };
            return nextConfig;
        },
        {...defaultConfig, backend: {...(defaultConfig.backend || {})}}
    );
}

/** 加载完整的配置文件（包括 appConfig 和 fileConfig） */
function loadFullFile() {
    const configPath = getUserConfigPath();
    const defaultStructure = {
        appConfig: mergeAppConfig(defaultConfig),
        fileConfig: {},
    };

    if (!fs.existsSync(configPath)) return defaultStructure;

    try {
        const raw = fs.readFileSync(configPath, 'utf8');
        const parsed = JSON.parse(raw);
        return {
            appConfig: mergeAppConfig(parsed.appConfig),
            fileConfig: (parsed.fileConfig && typeof parsed.fileConfig === 'object') ? parsed.fileConfig : {},
        };
    } catch (error) {
        console.error('读取 config.json 失败:', error);
        return defaultStructure;
    }
}

/** 保存完整数据到 config.json */
function saveFullFile(fullData) {
    const configPath = getUserConfigPath();
    try {
        const normalizedPayload = {
            appConfig: mergeAppConfig(fullData.appConfig),
            fileConfig: (fullData.fileConfig && typeof fullData.fileConfig === 'object') ? fullData.fileConfig : {},
        };
        fs.writeFileSync(configPath, JSON.stringify(normalizedPayload, null, 4), 'utf8');
        return true;
    } catch (error) {
        console.error('写入 config.json 失败:', error);
        return false;
    }
}

/** 写入应用配置 */
function writeConfig(data) {
    const full = loadFullFile();
    full.appConfig = mergeAppConfig(full.appConfig, data);
    return saveFullFile(full);
}

/** 读取应用配置 */
function readConfig() {
    return loadFullFile().appConfig;
}

/** 读取文件相关配置 */
function readFileConfig() {
    return loadFullFile().fileConfig;
}

/** 写入文件相关配置 */
function writeFileConfig(data) {
    const full = loadFullFile();
    full.fileConfig = {...full.fileConfig, ...(data || {})};
    return saveFullFile(full);
}

// 导出所有工具函数供主进程使用
module.exports = {
    readConfig,
    writeConfig,
    readFileConfig,
    writeFileConfig,
    getJavaBinaryPath,
    getPathMappingFilePath,
    readPathMapping,
    getPathMappingValue,
    getOpenAiConfigPath,
};
