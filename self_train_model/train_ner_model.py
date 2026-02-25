"""
NER (命名实体识别) 模型训练脚本
基于 BERT+CRF 的序列标注模型

运行:
    1. 先生成训练数据: python generate_ner_data.py
    2. 再训练模型:     python train_ner_model.py

依赖:
    pip install torch transformers pytorch-crf seqeval scikit-learn tqdm
"""

import os
import json
import torch
import torch.nn as nn
from torch.optim import AdamW
from torch.utils.data import Dataset, DataLoader
from transformers import BertTokenizerFast, get_linear_schedule_with_warmup
from sklearn.model_selection import train_test_split
from seqeval.metrics import classification_report as seq_classification_report
from seqeval.metrics import f1_score as seq_f1_score
from tqdm import tqdm
import numpy as np

from ner_model import BertCRFNER

# 设置 HF 镜像 (国内用户)
os.environ['HF_ENDPOINT'] = 'https://hf-mirror.com'


# ============ 配置 ============
class Config:
    # 模型配置
    MODEL_NAME = "hfl/chinese-bert-wwm-ext"
    MAX_LENGTH = 128
    DROPOUT = 0.1

    # 训练配置
    BATCH_SIZE = 16
    EPOCHS = 15
    LEARNING_RATE = 3e-5
    CRF_LEARNING_RATE = 1e-3      # CRF 层使用更高的学习率
    WARMUP_RATIO = 0.1
    MAX_GRAD_NORM = 1.0
    TRAIN_SPLIT = 0.8

    # 路径配置
    DATA_PATH = "./ner_training_data.json"
    MODEL_SAVE_PATH = "./best_ner_model.pt"
    DEVICE = "cuda" if torch.cuda.is_available() else "cpu"


# ============ 标签定义 ============
LABEL_LIST = [
    "O",
    "B-TABLE", "I-TABLE",
    "B-COLUMN", "I-COLUMN",
    "B-VALUE", "I-VALUE",
    "B-TIME", "I-TIME",
    "B-AGG", "I-AGG",
    "B-OP", "I-OP",
    "B-KW", "I-KW",
    # === 新增实体类型 ===
    "B-LOC", "I-LOC",          # 地理位置
    "B-ORG", "I-ORG",          # 组织/公司
    "B-FILTER", "I-FILTER",    # 过滤条件
]
LABEL2ID = {label: i for i, label in enumerate(LABEL_LIST)}
ID2LABEL = {i: label for i, label in enumerate(LABEL_LIST)}
NUM_LABELS = len(LABEL_LIST)


# ============ 数据集定义 ============
class NERDataset(Dataset):
    """NER BIO 标注数据集"""

    def __init__(self, data, tokenizer, max_length=128):
        self.data = data
        self.tokenizer = tokenizer
        self.max_length = max_length

    def __len__(self):
        return len(self.data)

    def __getitem__(self, idx):
        item = self.data[idx]
        chars = item['chars']
        labels = item['labels']

        # 使用 BERT Tokenizer 进行分词
        # is_split_into_words=True 表示输入已经是分好的字符/词列表
        encoding = self.tokenizer(
            chars,
            is_split_into_words=True,
            max_length=self.max_length,
            padding='max_length',
            truncation=True,
            return_tensors='pt'
        )

        # ===== 标签对齐 =====
        # BERT WordPiece 分词可能将一个字符拆成多个 subword token
        # 需要将原始字符级标签对齐到 token 级
        word_ids = encoding.word_ids()
        aligned_labels = []
        previous_word_id = None

        for word_id in word_ids:
            if word_id is None:
                # Special tokens ([CLS], [SEP], [PAD]) -> 忽略
                aligned_labels.append(-100)
            elif word_id != previous_word_id:
                # 新词/字符的第一个 token -> 使用原始标签
                if word_id < len(labels):
                    aligned_labels.append(LABEL2ID.get(labels[word_id], 0))
                else:
                    aligned_labels.append(-100)
            else:
                # 同一词/字符的后续 subword token
                # B- 标签转为 I- 标签, 其余保持
                if word_id < len(labels):
                    label = labels[word_id]
                    if label.startswith("B-"):
                        aligned_labels.append(LABEL2ID.get("I-" + label[2:], 0))
                    else:
                        aligned_labels.append(LABEL2ID.get(label, 0))
                else:
                    aligned_labels.append(-100)
            previous_word_id = word_id

        return {
            'input_ids': encoding['input_ids'].squeeze(0),
            'attention_mask': encoding['attention_mask'].squeeze(0),
            'labels': torch.tensor(aligned_labels, dtype=torch.long),
        }


# ============ 训练函数 ============
def train_epoch(model, dataloader, optimizer, scheduler, device):
    """训练一个 epoch"""
    model.train()
    total_loss = 0
    num_batches = 0

    for batch in tqdm(dataloader, desc="训练"):
        input_ids = batch['input_ids'].to(device)
        attention_mask = batch['attention_mask'].to(device)
        labels = batch['labels'].to(device)

        # 前向传播
        loss = model(input_ids, attention_mask, labels)

        # 反向传播
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), Config.MAX_GRAD_NORM)
        optimizer.step()
        scheduler.step()
        optimizer.zero_grad()

        total_loss += loss.item()
        num_batches += 1

    return total_loss / max(num_batches, 1)


# ============ 评估函数 ============
def evaluate(model, dataloader, device):
    """评估模型, 使用 seqeval 计算 entity-level 指标"""
    model.eval()
    all_preds = []
    all_labels = []

    with torch.no_grad():
        for batch in tqdm(dataloader, desc="评估"):
            input_ids = batch['input_ids'].to(device)
            attention_mask = batch['attention_mask'].to(device)
            labels_batch = batch['labels']

            # CRF Viterbi 解码
            predictions = model(input_ids, attention_mask)

            # 将预测结果和真实标签转换为标签字符串列表
            for pred_seq, label_seq, mask_seq in zip(
                predictions, labels_batch, attention_mask
            ):
                pred_labels = []
                true_labels = []

                label_np = label_seq.cpu().numpy()
                mask_np = mask_seq.cpu().numpy()
                for pred_id, label_id, mask_val in zip(
                    pred_seq,
                    label_np,
                    mask_np
                ):
                    # 只统计有效位置 (mask=1 且 label != -100)
                    if mask_val == 1 and label_id != -100:
                        pred_labels.append(ID2LABEL.get(pred_id, "O"))
                        true_labels.append(ID2LABEL.get(int(label_id), "O"))

                if pred_labels and true_labels:
                    all_preds.append(pred_labels)
                    all_labels.append(true_labels)

    # 使用 seqeval 计算 entity-level 指标
    f1 = seq_f1_score(all_labels, all_preds, average='macro', zero_division=0)
    report = seq_classification_report(all_labels, all_preds, zero_division=0)

    return f1, report


# ============ 主函数 ============
def main():
    print("=" * 60)
    print("NER 命名实体识别模型训练")
    print("=" * 60)

    config = Config()

    print(f"\n配置:")
    print(f"  设备: {config.DEVICE}")
    print(f"  模型: {config.MODEL_NAME}")
    print(f"  标签数: {NUM_LABELS}")
    print(f"  标签集: {LABEL_LIST}")
    print(f"  Batch Size: {config.BATCH_SIZE}")
    print(f"  Epochs: {config.EPOCHS}")
    print(f"  学习率: BERT={config.LEARNING_RATE}, CRF={config.CRF_LEARNING_RATE}")
    if torch.cuda.is_available():
        print(f"  GPU: {torch.cuda.get_device_name(0)}")

    # ===== 加载数据 =====
    print(f"\n加载数据: {config.DATA_PATH}")
    if not os.path.exists(config.DATA_PATH):
        print("  [ERROR] 数据文件不存在！请先运行 generate_ner_data.py")
        return

    with open(config.DATA_PATH, 'r', encoding='utf-8') as f:
        data = json.load(f)
    print(f"  总数据量: {len(data)}")

    # 数据统计
    entity_count = sum(1 for sample in data for l in sample['labels'] if l.startswith('B-'))
    print(f"  总实体数: {entity_count}")

    # ===== 划分数据集 =====
    train_data, val_data = train_test_split(
        data, test_size=1 - config.TRAIN_SPLIT, random_state=42
    )
    print(f"  训练集: {len(train_data)}")
    print(f"  验证集: {len(val_data)}")

    # ===== 加载 Tokenizer =====
    print(f"\n加载 Tokenizer: {config.MODEL_NAME}")
    try:
        tokenizer = BertTokenizerFast.from_pretrained(config.MODEL_NAME)
        print("  [OK] Tokenizer 加载成功")
    except Exception as e:
        print(f"  [ERROR] Tokenizer 加载失败: {e}")
        print("  请检查网络连接或使用本地模型")
        return

    # ===== 创建数据集和 DataLoader =====
    train_dataset = NERDataset(train_data, tokenizer, config.MAX_LENGTH)
    val_dataset = NERDataset(val_data, tokenizer, config.MAX_LENGTH)

    train_loader = DataLoader(
        train_dataset,
        batch_size=config.BATCH_SIZE,
        shuffle=True,
        num_workers=0,
    )
    val_loader = DataLoader(
        val_dataset,
        batch_size=config.BATCH_SIZE,
        shuffle=False,
        num_workers=0,
    )

    # ===== 创建模型 =====
    print(f"\n创建 BERT+CRF NER 模型...")
    model = BertCRFNER(
        model_name=config.MODEL_NAME,
        num_labels=NUM_LABELS,
        dropout=config.DROPOUT,
    ).to(config.DEVICE)
    print("  [OK] 模型创建成功")

    # 统计参数量
    total_params = sum(p.numel() for p in model.parameters())
    trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    print(f"  总参数量: {total_params:,}")
    print(f"  可训练参数: {trainable_params:,}")

    # ===== 优化器 (差异化学习率) =====
    # BERT 层使用较小的学习率, 分类层和 CRF 层使用较大的学习率
    optimizer = AdamW([
        {'params': model.get_bert_parameters(), 'lr': config.LEARNING_RATE},
        {'params': model.get_classifier_parameters(), 'lr': config.CRF_LEARNING_RATE},
    ], eps=1e-8)

    # ===== 学习率调度 =====
    total_steps = len(train_loader) * config.EPOCHS
    warmup_steps = int(total_steps * config.WARMUP_RATIO)
    scheduler = get_linear_schedule_with_warmup(
        optimizer,
        num_warmup_steps=warmup_steps,
        num_training_steps=total_steps,
    )

    # ===== 训练循环 =====
    print(f"\n开始训练...")
    print(f"  总步数: {total_steps}")
    print(f"  预热步数: {warmup_steps}")

    best_f1 = 0

    for epoch in range(config.EPOCHS):
        print(f"\n{'=' * 60}")
        print(f"Epoch {epoch + 1}/{config.EPOCHS}")
        print(f"{'=' * 60}")

        # 训练
        train_loss = train_epoch(
            model, train_loader, optimizer, scheduler, config.DEVICE
        )
        print(f"训练损失: {train_loss:.4f}")

        # 评估
        f1, report = evaluate(model, val_loader, config.DEVICE)
        print(f"验证 Entity F1 (macro): {f1:.4f}")
        print(f"\n详细报告:\n{report}")

        # 保存最佳模型
        if f1 > best_f1:
            best_f1 = f1
            torch.save({
                'epoch': epoch,
                'model_state_dict': model.state_dict(),
                'f1': best_f1,
                'config': {
                    'model_name': config.MODEL_NAME,
                    'num_labels': NUM_LABELS,
                    'label_list': LABEL_LIST,
                    'label2id': LABEL2ID,
                    'id2label': ID2LABEL,
                    'max_length': config.MAX_LENGTH,
                }
            }, config.MODEL_SAVE_PATH)
            print(f"  [OK] 保存最佳模型 (F1: {best_f1:.4f})")

    print(f"\n{'=' * 60}")
    print(f"训练完成！最佳 Entity F1: {best_f1:.4f}")
    print(f"模型保存至: {config.MODEL_SAVE_PATH}")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    main()
