package com.velinx.core.memory.embedding.db;

import dev.langchain4j.model.embedding.EmbeddingModel;
// 👇 这里是修改后的正确导入路径
import dev.langchain4j.model.embedding.onnx.bgesmallzhq.BgeSmallZhQuantizedEmbeddingModel;

public class BgeEmbeddingConfig {

    public EmbeddingModel localBgeEmbeddingModel() {
        // 实例化内置的量化模型，它会自动从依赖中读取模型
        return new BgeSmallZhQuantizedEmbeddingModel();
    }

    public static void main(String[] args) {
        BgeEmbeddingConfig config = new BgeEmbeddingConfig();
        EmbeddingModel model = config.localBgeEmbeddingModel();

        // 测试生成向量
        float[] vector = model.embed("这是一个测试本地化加载的中文句子").content().vector();

        System.out.println("向量维度: " + vector.length); // 正常应该输出 512
        System.out.println("模型加载并运行成功！");
    }
}