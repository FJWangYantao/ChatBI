"""
BERT+CRF 中文命名实体识别模型定义

Architecture:
    Input -> BERT (chinese-bert-wwm-ext) -> Dropout -> Linear -> CRF -> BIO Tags

标签集 (15 个):
    O, B-TABLE, I-TABLE, B-COLUMN, I-COLUMN, B-VALUE, I-VALUE,
    B-TIME, I-TIME, B-AGG, I-AGG, B-OP, I-OP, B-KW, I-KW

依赖:
    pip install pytorch-crf  (即 torchcrf)
"""

import torch
import torch.nn as nn
from transformers import BertModel
from torchcrf import CRF


class BertCRFNER(nn.Module):
    """
    BERT + CRF 序列标注模型

    使用 BERT 提取上下文表示, 通过 Linear 层映射到标签空间,
    最后用 CRF 层建模标签间的转移约束 (如 I-TABLE 必须跟在 B-TABLE 之后)。
    """

    def __init__(self, model_name, num_labels, dropout=0.1):
        """
        Args:
            model_name: HuggingFace 预训练模型名 (如 hfl/chinese-bert-wwm-ext)
            num_labels: BIO 标签数量
            dropout: Dropout 概率
        """
        super().__init__()
        self.num_labels = num_labels

        # BERT 基座模型
        self.bert = BertModel.from_pretrained(model_name)
        self.dropout = nn.Dropout(dropout)

        # 标签分类层: hidden_size -> num_labels
        self.classifier = nn.Linear(self.bert.config.hidden_size, num_labels)

        # CRF 层: 建模标签间转移概率
        self.crf = CRF(num_labels, batch_first=True)

    def forward(self, input_ids, attention_mask, labels=None):
        """
        前向传播

        Args:
            input_ids:      [batch_size, seq_len] - token IDs
            attention_mask: [batch_size, seq_len] - attention mask (1=valid, 0=padding)
            labels:         [batch_size, seq_len] - BIO 标签索引, -100 表示忽略的位置

        Returns:
            训练模式 (labels != None): 返回 CRF 负对数似然损失 (scalar)
            推理模式 (labels == None): 返回 Viterbi 解码的标签序列 (List[List[int]])
        """
        # BERT 编码
        outputs = self.bert(input_ids=input_ids, attention_mask=attention_mask)
        sequence_output = outputs.last_hidden_state  # [batch, seq_len, hidden_size]
        sequence_output = self.dropout(sequence_output)

        # 映射到标签空间
        emissions = self.classifier(sequence_output)  # [batch, seq_len, num_labels]

        if labels is not None:
            # ===== 训练模式 =====
            # CRF 不支持 -100 标签, 且要求首位置 mask 必须为 True
            # 1. 将 -100 替换为 0 (O)
            # 2. 使用 attention_mask 作为 mask ([CLS]/[SEP] 参与计算, 标签为 O)
            crf_labels = labels.clone()
            crf_labels[crf_labels == -100] = 0

            mask = attention_mask.bool()

            # CRF 前向: 计算负对数似然
            loss = -self.crf(emissions, crf_labels, mask=mask, reduction='mean')
            return loss
        else:
            # ===== 推理模式 =====
            mask = attention_mask.bool()
            predictions = self.crf.decode(emissions, mask=mask)
            return predictions

    def get_bert_parameters(self):
        """获取 BERT 层的参数 (用于差异化学习率)"""
        return self.bert.parameters()

    def get_classifier_parameters(self):
        """获取分类层和 CRF 层的参数"""
        return list(self.classifier.parameters()) + list(self.crf.parameters())
