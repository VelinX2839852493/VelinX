let currentBgImgPath = '';
let selectedModelPath = null;

document.addEventListener('DOMContentLoaded', async () => {
    const api = window.electronAPI && window.electronAPI.settings;
    if (!api) {
        return;
    }

    const colorInput = document.getElementById('bg_color');
    const saveBtn = document.getElementById('saveBtn');
    const selectModelBtn = document.getElementById('selectModelBtn');
    const selectBgBtn = document.getElementById('selectBgImgBtn');
    const clearBgBtn = document.getElementById('clearBgImgBtn');
    const resetColorBtn = document.getElementById('resetBgColorBtn');
    const debugBtn = document.getElementById('debugBtn');
    const modelPathDisplay = document.getElementById('modelPathDisplay');
    const bgImgPathDisplay = document.getElementById('bgImgPathDisplay');

    try {
        const currentConfig = await api.getCurrentConfig();
        document.getElementById('width').value = currentConfig.width;
        document.getElementById('height').value = currentConfig.height;
        document.getElementById('img_width').value = currentConfig.img_width;
        document.getElementById('img_height').value = currentConfig.img_height;

        if (currentConfig.model_path) {
            modelPathDisplay.textContent = `当前模型: ${currentConfig.model_path}`;
        }

        if (currentConfig.background_color) {
            colorInput.value = currentConfig.background_color;
            colorInput.dataset.isTransparent = 'false';
        } else {
            colorInput.value = '#ffffff';
            colorInput.dataset.isTransparent = 'true';
        }

        if (currentConfig.background_image) {
            currentBgImgPath = currentConfig.background_image;
            bgImgPathDisplay.textContent = `已选择: ${currentBgImgPath}`;
        }

        const penetrationCheckbox = document.getElementById('is_mouse_penetration');
        if (penetrationCheckbox) {
            penetrationCheckbox.checked = !!currentConfig.is_mouse_penetration;
        }
    } catch (error) {
        console.error('Failed to load settings:', error);
    }

    selectModelBtn.addEventListener('click', async () => {
        const selectedPath = await api.selectModelFolder();
        if (!selectedPath) {
            return;
        }

        selectedModelPath = selectedPath;
        modelPathDisplay.textContent = `已选择: ${selectedPath}`;
    });

    selectBgBtn.addEventListener('click', async () => {
        const selectedPath = await api.selectBackgroundImage();
        if (!selectedPath) {
            return;
        }

        currentBgImgPath = selectedPath;
        bgImgPathDisplay.textContent = `已选择: ${selectedPath}`;
    });

    clearBgBtn.addEventListener('click', () => {
        currentBgImgPath = '';
        bgImgPathDisplay.textContent = '当前未设置背景图片';
    });

    resetColorBtn.addEventListener('click', () => {
        colorInput.value = '#ffffff';
        colorInput.dataset.isTransparent = 'true';
    });

    colorInput.addEventListener('input', () => {
        colorInput.dataset.isTransparent = 'false';
    });

    saveBtn.addEventListener('click', async () => {
        const penetrationCheckbox = document.getElementById('is_mouse_penetration');
        let backgroundColor = colorInput.value;

        if (colorInput.dataset.isTransparent === 'true') {
            backgroundColor = '';
        }

        const config = {
            width: Number.parseInt(document.getElementById('width').value, 10) || 400,
            height: Number.parseInt(document.getElementById('height').value, 10) || 600,
            img_width: Number.parseInt(document.getElementById('img_width').value, 10) || 800,
            img_height: Number.parseInt(document.getElementById('img_height').value, 10) || 1200,
            is_mouse_penetration: penetrationCheckbox ? penetrationCheckbox.checked : false,
            background_color: backgroundColor,
            background_image: currentBgImgPath,
        };

        const result = await api.save({
            config,
            modelPath: selectedModelPath,
        });

        if (result && result.success === false) {
            window.alert(result.error || '保存失败');
        }
    });

    debugBtn.addEventListener('click', async () => {
        await api.toggleDebug();
    });
});
