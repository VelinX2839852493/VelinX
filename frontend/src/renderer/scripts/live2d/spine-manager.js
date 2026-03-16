const PIXI = require('pixi.js');
const fs = require('fs');
const path = require('path');

const Spine38 = require('@pixi-spine/all-3.8');
const Spine41 = require('@pixi-spine/all-4.1');

class SpineManager {
    constructor(app) {
        this.app = app;
        this.model = null;
        this.currentRuntime = null;
        // 新增：用于计算碰撞的工具类
        this.skeletonBounds = null;
        this._updateDebugFn = null; // 【新增】用于存放 ticker 回调函数
        // 【核心解耦：状态存储】
        this.exConfig = null;
        this.variables = {
            i: 0,           // 步进计数器
            lastMotion: ""  // 上一次执行的动作组
        };
    }

    detectSpineVersion(fileBuffer, isJson) {
        try {
            if (isJson) {
                const json = JSON.parse(fileBuffer.toString('utf8'));
                if (json.skeleton && json.skeleton.spine) return String(json.skeleton.spine);
            } else {
                const header = fileBuffer.slice(0, 64).toString('binary');
                const match = header.match(/\d+\.\d+\.\d+/);
                if (match) return match[0];
            }
        } catch (e) {
        }
        return "3.8";
    }

    async load(skelPath, winWidth, winHeight, imgWidth, imgHeight) {
        if (!skelPath || !fs.existsSync(skelPath)) return false;

        try {
            const isJson = skelPath.endsWith('.json');
            const atlasPath = skelPath.replace(isJson ? '.json' : '.skel', '.atlas');
            const dirPath = path.dirname(skelPath);
            const fileBuffer = fs.readFileSync(skelPath);

            const versionStr = this.detectSpineVersion(fileBuffer, isJson);
            const isV4 = versionStr.startsWith("4.");
            this.currentRuntime = isV4 ? Spine41 : Spine38;

            if (this.model) {
                this.app.stage.removeChild(this.model);
                this.model.destroy({children: true, texture: false, baseTexture: false});
            }

            // 1. 解析 Atlas 并获取全局 Scale
            const atlasText = fs.readFileSync(atlasPath, 'utf8');
            const lines = atlasText.split(/\r?\n/);
            const pages = [];

            // 尝试从 Atlas 头部匹配全局 scale (Spine 4 特有)
            let atlasScale = 1.0;
            const scaleMatch = atlasText.match(/^scale:\s*([\d.]+)/m);
            if (scaleMatch) {
                atlasScale = parseFloat(scaleMatch[1]);
                console.log(`[Spine] 检测到 Atlas Scale: ${atlasScale}`);
            }

            for (let i = 0; i < lines.length; i++) {
                const line = lines[i].trim();
                if (line.match(/\.(png|jpg|jpeg|webp)$/i)) {
                    let pageInfo = {name: line, width: 0, height: 0};
                    if (lines[i + 1] && lines[i + 1].includes('size:')) {
                        const sizes = lines[i + 1].split(':')[1].split(',');
                        pageInfo.width = parseInt(sizes[0]);
                        pageInfo.height = parseInt(sizes[1]);
                    }
                    pages.push(pageInfo);
                }
            }

            // 2. 加载贴图 (改“补齐”为“拉伸”)
            const textureCache = new Map();
            await Promise.all(pages.map(async (page) => {
                const fullPath = path.join(dirPath, page.name);
                const tex = await this.createFixedTexture(fullPath, page.width, page.height);
                textureCache.set(page.name, tex);
            }));

            const spineAtlas = new this.currentRuntime.TextureAtlas(atlasText, (line, callback) => {
                const tex = textureCache.get(line);
                if (tex) callback(tex);
                else console.error(`[Spine] 贴图缺失: ${line}`);
            });
            const atlasLoader = new this.currentRuntime.AtlasAttachmentLoader(spineAtlas);

            // 3. 解析骨骼数据 (关键：应用 atlasScale)
            let skeletonData;
            if (isJson) {
                const jsonParser = new this.currentRuntime.SkeletonJson(atlasLoader);
                // 如果散架严重，通常是因为这里的 scale 没对上贴图的 scale
                jsonParser.scale = atlasScale;
                skeletonData = jsonParser.readSkeletonData(JSON.parse(fileBuffer.toString('utf8')));
            } else {
                const binaryParser = new this.currentRuntime.SkeletonBinary(atlasLoader);
                binaryParser.scale = atlasScale;
                skeletonData = binaryParser.readSkeletonData(new Uint8Array(fileBuffer));
            }


            // 尝试加载 Live2DViewerEX 的配置文件
            const jsonPath = skelPath.replace(/[^\\\/]+$/, 'model0.json');
            // 注意：如果你的 skel 叫 model.skel，配置文件可能叫 model.json
            // 但如果 skel 叫 character.skel，而配置叫 model.json，你需要根据实际情况调整路径

            if (fs.existsSync(jsonPath)) {
                const configData = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
                if (configData.hit_areas) {
                    this.exConfig = configData;
                    console.log("[Spine] 已加载 Live2DViewerEX 触摸配置");
                }
            }

            this.model = new this.currentRuntime.Spine(skeletonData);
            this.model.autoUpdate = true;

            // ✅ 新增：初始化碰撞检测器
            this.skeletonBounds = new this.currentRuntime.SkeletonBounds();


            // 4. 设置混合模式 (解决黑边/透明度问题)
            this.model.skeleton.alpha = 1;

            const skin = skeletonData.defaultSkin || (skeletonData.skins && skeletonData.skins[0]);
            if (skin) this.model.skeleton.setSkin(skin);
            this.model.skeleton.setSlotsToSetupPose();


            // 绑定点击事件
            this.model.interactive = true; // 确保交互开启
            this.model.on('pointertap', (e) => {
                const localPos = this.model.toLocal(e.data.global);
                const hitResult = this.handleTap(localPos.x, localPos.y);

                if (hitResult) {
                    // 这里说明点中了有效区域
                    console.log("外部触发：点中了区域", hitResult.name);
                    // 你可以在这里触发其他的全局事件，比如：
                    // this.app.emit('spine_hit', hitResult);
                } else {
                    // 说明点在了模型身上，但不是定义的碰撞盒区域
                    console.log("外部触发：未点中有效区域");
                }
            });


            this.app.stage.addChild(this.model);
            this.resize(winWidth, winHeight, imgWidth, imgHeight);
            this.playDefaultAnimation();

            // ✅ 新增：加载完成后立即打印所有区域
            this.logAllBoundingBoxes();

            console.log(`[Spine] ${versionStr} 加载成功`);
            return true;
        } catch (error) {
            // 1. 打印完整的错误堆栈到控制台
            console.error("===== SPINE LOAD ERROR START =====");
            console.error("错误信息:", error.message);
            console.error("堆栈轨迹:", error.stack);
            console.error("===== SPINE LOAD ERROR END =====");

            return false;
        }
    }

    /**
     * 核心修复：拉伸纹理以匹配 UV
     */
    async createFixedTexture(imgPath, fixW, fixH) {
        return new Promise((resolve, reject) => {
            if (!fs.existsSync(imgPath)) return reject(`文件不存在: ${imgPath}`);
            const bitmap = fs.readFileSync(imgPath);
            const dataUrl = `data:image/png;base64,${bitmap.toString('base64')}`;
            const img = new Image();
            img.onload = () => {
                // 如果没有声明尺寸，或者尺寸刚好一致，直接返回
                if (!fixW || !fixH || (img.width === fixW && img.height === fixH)) {
                    resolve(PIXI.BaseTexture.from(img));
                    return;
                }

                // 重点：必须使用 drawImage 的拉伸参数 (img, sx, sy, sw, sh, dx, dy, dw, dh)
                // 这样原本 2048 的图会被放大到 4096，UV 坐标才能正确采样到像素
                const canvas = document.createElement('canvas');
                canvas.width = fixW;
                canvas.height = fixH;
                const ctx = canvas.getContext('2d');
                ctx.drawImage(img, 0, 0, img.width, img.height, 0, 0, fixW, fixH);

                console.log(`[Spine] 纹理拉伸修正: ${img.width}x${img.height} -> ${fixW}x${fixH}`);
                const baseTex = PIXI.BaseTexture.from(canvas);
                resolve(baseTex);
            };
            img.onerror = () => reject(`图片加载失败: ${imgPath}`);
            img.src = dataUrl;
        });
    }

    resize(winWidth, winHeight, imgWidth, imgHeight) {
        if (!this.model) return;

        this.model.updateTransform();
        const bounds = this.model.getLocalBounds();

        const ratioX = imgWidth / (bounds.width || 500);
        const ratioY = imgHeight / (bounds.height || 500);
        const finalScale = Math.min(ratioX, ratioY);
        this.model.scale.set(finalScale);

        // 模型的中心点坐标
        const centerX = bounds.x + bounds.width / 2;
        const centerY = bounds.y + bounds.height / 2;

        // 对齐到窗口正中心
        this.model.x = winWidth / 2 - centerX * finalScale;
        this.model.y = winHeight / 2 - centerY * finalScale;
    }


    /**
     * 遍历并打印当前模型中所有定义的碰撞盒及其坐标
     */
    logAllBoundingBoxes() {
        if (!this.model) return;

        console.log("%c=== [Spine] 扫描可触摸区域 (Bounding Boxes) ===", "color: #00CCFF; font-weight: bold;");

        // 强制更新一次边界计算
        this.skeletonBounds.update(this.model.skeleton, true);

        const boxes = this.skeletonBounds.boundingBoxes; // 附件对象数组
        const polygons = this.skeletonBounds.polygons;   // 对应的顶点数组

        if (boxes.length === 0) {
            console.warn("[Spine] 未在模型中找到任何 BoundingBox 附件。");
        }

        boxes.forEach((box, index) => {
            const polygon = polygons[index];
            // 计算多边形的包围盒 (Min/Max)
            let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
            for (let i = 0; i < polygon.length; i += 2) {
                const x = polygon[i];
                const y = polygon[i + 1];
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }

            const width = maxX - minX;
            const height = maxY - minY;
            const centerX = minX + width / 2;
            const centerY = minY + height / 2;

            console.log(
                `[区域:${index}] 名称: "${box.name}"\n` +
                `   -> 中心点: [X: ${centerX.toFixed(2)}, Y: ${centerY.toFixed(2)}]\n` +
                `   -> 尺寸: [宽: ${width.toFixed(2)}, 高: ${height.toFixed(2)}]\n` +
                `   -> 范围: X(${minX.toFixed(2)} ~ ${maxX.toFixed(2)}), Y(${minY.toFixed(2)} ~ ${maxY.toFixed(2)})`
            );
        });

        // 如果有 L2DEX 配置，也打印出来
        if (this.exConfig && this.exConfig.hit_areas) {
            console.log("%c=== [L2DEX] 配置文件区域 ===", "color: #FF00FF; font-weight: bold;");
            const exUnit = 500;
            this.exConfig.hit_areas.forEach(area => {
                const w = (area.width || 0) * exUnit;
                const h = (area.height || 0) * exUnit;
                const cx = (area.center_x || 0) * exUnit;
                const cy = -(area.center_y || 0) * exUnit;
                console.log(`[配置] 区域: "${area.name}" -> 中心: [${cx}, ${cy}], 宽高: [${w}, ${h}]`);
            });
        }
    }

    /**
     * 物理更新 (关键：补齐接口，防止 reshader.js 报错)
     */
    updatePhysics(mouseX, mouseY) {
        if (!this.model) return;

        // Spine 默认不像 Live2D 有统一的转头参数
        // 如果你想实现简单的跟随，可以尝试控制骨骼：
        // const headBone = this.model.skeleton.findBone('head');
        // if(headBone) { ... 计算角度并更新 headBone ... }

        // 目前保持为空，仅作为接口占位，防止主循环 TypeError
    }

    playDefaultAnimation() {
        if (!this.model || !this.model.skeleton.data.animations.length) return;
        this.model.state.setAnimation(0, this.model.skeleton.data.animations[0].name, true);
    }

    getBounds() {
        return this.model ? this.model.getBounds() : {x: 0, y: 0, width: 0, height: 0};
    }

    /**
     * 抽取一个随机播放的方法
     */
    playRandomAnimation() {
        const animations = this.model.state.data.skeletonData.animations;
        if (animations.length > 0) {
            const tapAnim = animations.find(a => a.name.includes('tap'));
            const animName = tapAnim ? tapAnim.name : animations[Math.floor(Math.random() * animations.length)].name;
            this.model.state.setAnimation(0, animName, false);
            this.model.state.addAnimation(0, animations[0].name, true, 0);
        }
    }

    /**
     * 修改后的 toggleDebug：现在会显示具体的碰撞多边形
     */
    toggleDebug() {
        if (!this.model) return;

        if (this._updateDebugFn) {
            this.app.ticker.remove(this._updateDebugFn);
            this._updateDebugFn = null;
            if (this.debugGraphics) {
                this.model.removeChild(this.debugGraphics);
                this.debugGraphics.destroy();
                this.debugGraphics = null;
            }
            console.log("[Spine] Debug 绘制已关闭");
            return;
        }

        this.debugGraphics = new PIXI.Graphics();
        // 将 debug 图层添加到模型上，这样它会随模型一起缩放和位移
        this.model.addChild(this.debugGraphics);

        this._updateDebugFn = () => {
            if (!this.debugGraphics || this.debugGraphics.destroyed) return;

            this.debugGraphics.clear();

            // 1. 绘制 Spine 原生多边形 (蓝色)
            // 必须每帧 update 才能跟随骨骼动画移动
            this.skeletonBounds.update(this.model.skeleton, true);

            this.debugGraphics.lineStyle(2, 0x00CCFF, 1);
            this.skeletonBounds.polygons.forEach((poly) => {
                // poly 是 [x1, y1, x2, y2, ...] 格式
                this.debugGraphics.beginFill(0x00CCFF, 0.2);
                this.debugGraphics.drawPolygon(poly);
                this.debugGraphics.endFill();
            });

            // 2. 绘制 L2DEX 配置区域 (紫色矩形)
            if (this.exConfig && this.exConfig.hit_areas) {
                const exUnit = 500;
                this.debugGraphics.lineStyle(3, 0xFF00FF, 1);
                this.exConfig.hit_areas.forEach(area => {
                    const w = (area.width || 0) * exUnit;
                    const h = (area.height || 0) * exUnit;
                    const x = (area.center_x || 0) * exUnit - w / 2;
                    const y = -(area.center_y || 0) * exUnit - h / 2;

                    this.debugGraphics.beginFill(0xFF00FF, 0.1);
                    this.debugGraphics.drawRect(x, y, w, h);
                    this.debugGraphics.endFill();
                });
            }
        };

        this.app.ticker.add(this._updateDebugFn);
        console.log("[Spine] Debug 绘制已开启（蓝色为原生边界框，紫色为L2DEX配置）");
    }


    /**
     * 【万能逻辑解释器】
     * 自动兼容：
     * 1. 步进形式 (tap_pussy)
     * 2. 映射形式 (tick1:3)
     */
    playExMotion(motionGroupName) {
        if (!this.exConfig || !this.exConfig.motions || !motionGroupName) return;

        // --- 1. 拆分指令 (兼容 "tick1:3" 和 "tap_pussy") ---
        const parts = motionGroupName.split(':');
        const baseGroup = parts[0];     // "tick1" 或 "tap_pussy"
        const identifier = parts[1];    // "3" 或 undefined

        const group = this.exConfig.motions[baseGroup];
        if (!group || !Array.isArray(group)) {
            console.warn(`[状态机] 动作组未找到: ${baseGroup}`);
            return;
        }

        // --- 2. 定位具体的 Motion Entry ---
        let motionEntry = null;
        let currentIndex = 0;

        if (identifier) {
            // 【映射模式】：根据 ID (name 字段) 查找
            motionEntry = group.find(m => String(m.name) === identifier);
            // 记录当前索引用于后续 Flow 判断
            currentIndex = group.indexOf(motionEntry);
            console.log(`[状态机] 映射模式: ${baseGroup} -> ID:${identifier}`);
        } else {
            // 【步进模式】：使用该组专属的计数器
            if (this.variables[baseGroup] === undefined) {
                this.variables[baseGroup] = 0;
            }
            currentIndex = this.variables[baseGroup];
            motionEntry = group[currentIndex];
            // 计数器累加
            this.variables[baseGroup] = (currentIndex + 1) % group.length;
            console.log(`[状态机] 步进模式: ${baseGroup} -> Index:${currentIndex}`);
        }

        if (!motionEntry) {
            console.warn(`[状态机] 无法在组 ${baseGroup} 中定位动作`);
            return;
        }

        // --- 3. 提取动画文件名并执行 ---
        let animName = null;
        if (motionEntry.file) {
            animName = motionEntry.file.replace(/^.*[\\\/]/, '').replace(/\.[^/.]+$/, '');
        } else {
            // 兜底：如果没写 file，按索引猜动画 (如 10_Loop)
            animName = this.guessAnimationByIndex(currentIndex);
        }

        if (animName) {
            this.executeAdaptiveFlow(animName, currentIndex, group.length);
        } else {
            this.playRandomAnimation();
        }
    }

    /**
     * 【自适应动作流】
     * 解决割裂感：如果是中间动作，保持 Loop；如果是结尾，播完回 Idle
     */
    executeAdaptiveFlow(animName, currentIndex, totalSteps) {
        const animations = this.model.state.data.skeletonData.animations.map(a => a.name);

        // 增强版查找：不只是 includes，还要处理 touch -> 10_Loop_ani_touch 的情况
        const findAnim = (name) => animations.find(a =>
            a.toLowerCase() === name.toLowerCase() ||
            a.toLowerCase().includes(name.toLowerCase())
        );

        const mainAnim = findAnim(animName);
        if (!mainAnim) {
            console.warn(`[执行器] 模型中缺失动画: ${animName}`);
            // 即使名字不对，如果是步进模式，我们也强行播一个序列动画试试
            const fallback = this.guessAnimationByIndex(currentIndex);
            if (fallback && fallback !== animName) return this.executeAdaptiveFlow(fallback, currentIndex, totalSteps);
            return;
        }

        console.log(`[执行器] 播放阶段 ${currentIndex}: ${mainAnim}`);
        this.model.state.clearTrack(0);

        // 逻辑适配：
        // 1. 如果数组只有一个动作 (比如 tick1:3 对应的一个 touch)
        if (totalSteps === 1) {
            this.model.state.setAnimation(0, mainAnim, false);
            this.model.state.addAnimation(0, animations.find(a => a.includes('Idle')) || animations[0], true, 0);
            return;
        }

        // 2. 多阶段动作流判断
        const isFirst = (currentIndex === 0);
        const isLast = (currentIndex === totalSteps - 1);

        if (isFirst) {
            this.model.state.setAnimation(0, mainAnim, false);
            const loop = findAnim("loop") || findAnim("ani") || mainAnim;
            this.model.state.addAnimation(0, loop, true, 0);
        } else if (isLast) {
            this.model.state.setAnimation(0, mainAnim, false);
            const idle = findAnim("00_Idle") || findAnim("idle") || animations[0];
            this.model.state.addAnimation(0, idle, true, 0);
        } else {
            // 中间过程：如果名字里含 loop/ani，开启循环；否则播完停在最后一帧
            const shouldLoop = mainAnim.toLowerCase().includes('loop') || mainAnim.toLowerCase().includes('ani');
            this.model.state.setAnimation(0, mainAnim, shouldLoop);
        }
    }

    /**
     * 【重写：精准点击检测】
     * 解决你之前的“点击没用”：自动处理重叠区域，面积小的优先
     */
    handleTap(localX, localY) {
        if (!this.model || !this.exConfig || !this.exConfig.hit_areas) return null;

        const exUnit = 500;
        let bestHit = null;
        let minArea = Infinity;

        console.log(`[点击] 局部坐标: X=${localX.toFixed(2)}, Y=${localY.toFixed(2)}`);

        for (const area of this.exConfig.hit_areas) {
            const w = (area.width || 0) * exUnit;
            const h = (area.height || 0) * exUnit;
            const cx = (area.center_x || 0) * exUnit;
            const cy = -(area.center_y || 0) * exUnit;

            const halfW = w / 2;
            const halfH = h / 2;

            if (localX >= cx - halfW && localX <= cx + halfW &&
                localY >= cy - halfH && localY <= cy + halfH) {

                // 核心技巧：如果多个区域重叠（中心都是 0,0），点中面积最小的那个（通常是精准部位）
                const currentArea = w * h;
                if (currentArea < minArea) {
                    minArea = currentArea;
                    bestHit = area;
                }
            }
        }

        if (bestHit) {
            console.log(`%c[击中]: ${bestHit.name} -> Motion: ${bestHit.motion}`, "background: #00FF00; color: #000; padding: 2px;");
            this.playExMotion(bestHit.motion);
            return {type: 'l2dex', name: bestHit.name, data: bestHit};
        }

        console.log("未击中定义的 L2DEX 区域");
        return null;
    }

    /**
     * 【智能猜解】
     * 如果 JSON 没给动画名，根据索引 i 猜测模型里对应的动画
     */
    guessAnimationByIndex(index) {
        const animations = this.model.state.data.skeletonData.animations.map(a => a.name);
        // 尝试匹配带数字的动画，比如 index=1 匹配 "10_Loop", "01_In", "Action_1"
        const pattern = index.toString();
        return animations.find(name => {
            const numPart = name.match(/\d+/);
            return numPart && numPart[0].includes(pattern);
        });
    }

    /**
     * 彻底销毁模型并从舞台移除
     */
    destroy() {
        console.log("[SpineManager] 正在彻底销毁...");

        // 1. 【关键】首先停止并移除 Ticker 监听
        if (this._updateDebugFn) {
            this.app.ticker.remove(this._updateDebugFn);
            this._updateDebugFn = null;
        }

        // 2. 销毁调试图形
        if (this.debugGraphics) {
            if (!this.debugGraphics.destroyed) {
                this.debugGraphics.destroy();
            }
            this.debugGraphics = null;
        }

        // 3. 销毁模型
        if (this.model) {
            if (this.model.parent) {
                this.model.parent.removeChild(this.model);
            }
            if (!this.model.destroyed) {
                // 建议：如果你发现切换模型还是崩溃，这里可以尝试只用 this.model.destroy()
                // 某些版本的 pixi-spine 在递归销毁 children 时会触发已销毁对象的访问
                this.model.destroy({children: true, texture: false, baseTexture: false});
            }
            this.model = null;
        }
    }

}

module.exports = SpineManager;