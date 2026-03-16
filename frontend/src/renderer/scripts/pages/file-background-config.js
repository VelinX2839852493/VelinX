function replacePreview(previewNode, imagePath) {
    previewNode.replaceChildren();

    if (!imagePath) {
        previewNode.textContent = '未选择图片';
        return;
    }

    const image = document.createElement('img');
    image.src = `file://${imagePath.replace(/\\/g, '/')}`;
    previewNode.appendChild(image);
}

document.addEventListener('DOMContentLoaded', async () => {
    const api = window.electronAPI && window.electronAPI.fileUi;
    if (!api) {
        return;
    }

    const elements = {
        bgTypeRadios: Array.from(document.querySelectorAll('input[name="bgType"]')),
        colorControls: document.getElementById('color-controls'),
        imageControls: document.getElementById('image-controls'),
        customColor: document.getElementById('custom-color'),
        colorHex: document.getElementById('color-hex'),
        opacitySlider: document.getElementById('opacity-slider'),
        opacityValue: document.getElementById('opacity-value'),
        selectImageBtn: document.getElementById('select-image-btn'),
        imagePreview: document.getElementById('image-preview'),
        saveBtn: document.getElementById('save-btn'),
    };

    let config = {
        color: '#ffffff',
        opacity: 1,
        image: '',
        useImage: false,
    };

    function toggleSections(type) {
        elements.colorControls.style.display = type === 'color' ? 'block' : 'none';
        elements.imageControls.style.display = type === 'image' ? 'block' : 'none';
    }

    function syncConfigFromForm() {
        config.color = elements.customColor.value;
        config.opacity = Number(elements.opacitySlider.value) / 100;
        config.useImage = document.querySelector('input[name="bgType"]:checked').value === 'image';
    }

    function savePreview() {
        syncConfigFromForm();
        api.saveBackgroundConfig(config);
    }

    config = {
        ...config,
        ...(await api.getBackgroundConfig()),
    };

    elements.customColor.value = config.color;
    elements.colorHex.textContent = config.color;
    elements.opacitySlider.value = String(Math.round(config.opacity * 100));
    elements.opacityValue.textContent = `${Math.round(config.opacity * 100)}%`;

    const selectedType = config.useImage ? 'image' : 'color';
    const selectedRadio = document.querySelector(`input[value="${selectedType}"]`);
    if (selectedRadio) {
        selectedRadio.checked = true;
    }
    toggleSections(selectedType);
    replacePreview(elements.imagePreview, config.image);

    elements.bgTypeRadios.forEach((radio) => {
        radio.addEventListener('change', (event) => {
            toggleSections(event.target.value);
            savePreview();
        });
    });

    elements.customColor.addEventListener('input', (event) => {
        elements.colorHex.textContent = event.target.value;
        savePreview();
    });

    elements.opacitySlider.addEventListener('input', (event) => {
        elements.opacityValue.textContent = `${event.target.value}%`;
        savePreview();
    });

    elements.selectImageBtn.addEventListener('click', async () => {
        const selectedPath = await api.selectBackgroundImage();
        if (!selectedPath) {
            return;
        }

        config.image = selectedPath;
        replacePreview(elements.imagePreview, selectedPath);
        savePreview();
    });

    elements.saveBtn.addEventListener('click', () => {
        savePreview();
        window.close();
    });
});
