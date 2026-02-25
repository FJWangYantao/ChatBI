import os
import json
import random
import torch
import torch.nn as nn
from torch.optim import AdamW
from torch.utils.data import Dataset, DataLoader
from transformers import BertModel, BertTokenizer, get_linear_schedule_with_warmup
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score, f1_score, classification_report
from tqdm import tqdm
import numpy as np

# ============ 配置 ============
class Config:
    # 模型配置
    MODEL_NAME = "hfl/chinese-bert-wwm-ext"  # 使用 HF 镜像
    NUM_CATEGORIES = 4  # DATA_QUERY, GENERAL_CHAT, HYBRID, DATA_OPERATION

    NUM_SUBTYPES = 21   # 21种子类型（含 DATA_QUERY 15种 + DATA_OPERATION 4种 + HYBRID_QUERY + CHAT）
    MAX_LENGTH = 128
    DROPOUT = 0.1

    # 训练配置
    BATCH_SIZE = 16
    EPOCHS = 10
    LEARNING_RATE = 2e-5
    WARMUP_STEPS = 100
    TRAIN_SPLIT = 0.8

    # 路径配置
    DATA_PATH = "./synthetic_training_full.json"
    MODEL_SAVE_PATH = "./best_intent_model.pt"
    DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

    # 镜像配置（国内用户）
    os.environ['HF_ENDPOINT'] = 'https://hf-mirror.com'


# ============ 模型定义 ============
class IntentClassifier(nn.Module):
    def __init__(self, model_name, num_categories, num_subtypes, dropout=0.1):
        super().__init__()

        print(f"加载 BERT 模型: {model_name}")
        self.bert = BertModel.from_pretrained(model_name)
        self.dropout = nn.Dropout(dropout)

        hidden_size = self.bert.config.hidden_size

        # 一级分类器（意图类别）
        self.category_classifier = nn.Sequential(
            nn.Linear(hidden_size, 256),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(256, num_categories)
        )

        # 二级分类器（查询子类型）
        self.subtype_classifier = nn.Sequential(
            nn.Linear(hidden_size, 256),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(256, num_subtypes)
        )

    def forward(self, input_ids, attention_mask):
        outputs = self.bert(input_ids=input_ids, attention_mask=attention_mask)
        pooled_output = outputs.pooler_output
        pooled_output = self.dropout(pooled_output)

        category_logits = self.category_classifier(pooled_output)
        subtype_logits = self.subtype_classifier(pooled_output)

        return {
            'category_logits': category_logits,
            'subtype_logits': subtype_logits
        }


# ============ 数据集定义 ============
class IntentDataset(Dataset):
    def __init__(self, data, tokenizer, max_length=128):
        self.data = data
        self.tokenizer = tokenizer
        self.max_length = max_length

        self.category2id = {
            "DATA_QUERY": 0,
            "GENERAL_CHAT": 1,
            "HYBRID": 2,
            "DATA_OPERATION": 3
        }

        self.subtype2id = {
            # DATA_QUERY subtypes (15)
            "AGGREGATION_SUM": 0, "AGGREGATION_COUNT": 1, "AGGREGATION_AVG": 2,
            "AGGREGATION_MAX_MIN": 3, "DETAIL_LIST": 4, "DETAIL_SINGLE": 5,
            "DETAIL_SEARCH": 6, "TREND_ANALYSIS": 7, "COMPARISON_ANALYSIS": 8,
            "RANKING_ANALYSIS": 9, "DISTRIBUTION_ANALYSIS": 10,
            "JOIN_QUERY": 11, "SUB_QUERY": 12, "METADATA_QUERY": 13,
            "ROOT_CAUSE_ANALYSIS": 14,
            # DATA_OPERATION subtypes (4)
            "CREATE_OPERATION": 15, "UPDATE_OPERATION": 16,
            "DELETE_OPERATION": 17, "EXPORT_OPERATION": 18,
            # Other subtypes
            "HYBRID_QUERY": 19, "CHAT": 20,
        }
        self.id2category = {v: k for k, v in self.category2id.items()}
        self.id2subtype = {v: k for k, v in self.subtype2id.items()}

    def __len__(self):
        return len(self.data)

    def __getitem__(self, idx):
        item = self.data[idx]

        # Tokenize
        encoding = self.tokenizer(
            item['text'],
            max_length=self.max_length,
            padding='max_length',
            truncation=True,
            return_tensors='pt'
        )

        # 处理 sub_type 为 None 的情况
        subtype = item.get('sub_type')
        if subtype is None:
            subtype_label = 20  # CHAT (默认)
        else:
            subtype_label = self.subtype2id.get(subtype, 20)

        return {
            'input_ids': encoding['input_ids'].squeeze(0),
            'attention_mask': encoding['attention_mask'].squeeze(0),
            'category_label': torch.tensor(self.category2id[item['category']], dtype=torch.long),
            'subtype_label': torch.tensor(subtype_label, dtype=torch.long)
        }


# ============ 训练和评估函数 ============
def train_epoch(model, dataloader, optimizer, scheduler, device):
    model.train()
    total_loss = 0
    category_preds, category_labels = [], []
    subtype_preds, subtype_labels = [], []

    for batch in tqdm(dataloader, desc="训练"):
        input_ids = batch['input_ids'].to(device)
        attention_mask = batch['attention_mask'].to(device)
        category_labels_batch = batch['category_label'].to(device)
        subtype_labels_batch = batch['subtype_label'].to(device)

        # 前向传播
        outputs = model(input_ids, attention_mask)

        # 计算损失（多任务）
        category_loss = nn.CrossEntropyLoss()(
            outputs['category_logits'],
            category_labels_batch
        )
        subtype_loss = nn.CrossEntropyLoss()(
            outputs['subtype_logits'],
            subtype_labels_batch
        )

        # 所有类别都有子类型，全部计算 subtype 损失
        total_batch_loss = category_loss + subtype_loss

        # 反向传播
        total_batch_loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        scheduler.step()
        optimizer.zero_grad()

        total_loss += total_batch_loss.item()

        # 记录预测
        category_preds.extend(outputs['category_logits'].argmax(dim=1).cpu().numpy())
        category_labels.extend(category_labels_batch.cpu().numpy())
        subtype_preds.extend(outputs['subtype_logits'].argmax(dim=1).cpu().numpy())
        subtype_labels.extend(subtype_labels_batch.cpu().numpy())

    # 计算指标
    category_acc = accuracy_score(category_labels, category_preds)
    subtype_acc = accuracy_score(subtype_labels, subtype_preds)

    return total_loss / len(dataloader), category_acc, subtype_acc


def evaluate(model, dataloader, device):
    model.eval()
    category_preds, category_labels = [], []
    subtype_preds, subtype_labels = [], []

    with torch.no_grad():
        for batch in tqdm(dataloader, desc="评估"):
            input_ids = batch['input_ids'].to(device)
            attention_mask = batch['attention_mask'].to(device)

            outputs = model(input_ids, attention_mask)

            category_preds.extend(outputs['category_logits'].argmax(dim=1).cpu().numpy())
            category_labels.extend(batch['category_label'].numpy())
            subtype_preds.extend(outputs['subtype_logits'].argmax(dim=1).cpu().numpy())
            subtype_labels.extend(batch['subtype_label'].numpy())

    category_acc = accuracy_score(category_labels, category_preds)
    category_f1 = f1_score(category_labels, category_preds, average='macro', zero_division=0)
    subtype_acc = accuracy_score(subtype_labels, subtype_preds)
    subtype_f1 = f1_score(subtype_labels, subtype_preds, average='macro', zero_division=0)

    return {
        'category_accuracy': category_acc,
        'category_f1': category_f1,
        'subtype_accuracy': subtype_acc,
        'subtype_f1': subtype_f1
    }


# ============ 主函数 ============
def main():
    print("=" * 60)
    print("意图识别模型训练")
    print("=" * 60)

    # 配置
    config = Config()
    print(f"\n配置:")
    print(f"  设备: {config.DEVICE}")
    print(f"  模型: {config.MODEL_NAME}")
    print(f"  Batch Size: {config.BATCH_SIZE}")
    print(f"  Epochs: {config.EPOCHS}")
    if torch.cuda.is_available():
        print(f"  GPU: {torch.cuda.get_device_name(0)}")

    # 加载数据
    print(f"\n加载数据: {config.DATA_PATH}")
    if not os.path.exists(config.DATA_PATH):
        print(f"  [FAIL] 数据文件不存在！")
        print(f"  请先运行 generate_full_data.py 生成数据")
        return

    with open(config.DATA_PATH, 'r', encoding='utf-8') as f:
        data = json.load(f)

    print(f"  [OK] 总数据量: {len(data)}")

    # 划分数据集
    train_data, val_data = train_test_split(
        data,
        test_size=1 - config.TRAIN_SPLIT,
        random_state=42,
        stratify=[d['category'] for d in data]
    )
    print(f"  训练集: {len(train_data)}")
    print(f"  验证集: {len(val_data)}")

    # 加载 Tokenizer
    print(f"\n加载 Tokenizer...")
    try:
        tokenizer = BertTokenizer.from_pretrained(config.MODEL_NAME)
        print(f"  [OK] Tokenizer 加载成功")
    except Exception as e:
        print(f"  [FAIL] Tokenizer 加载失败: {e}")
        print(f"  请检查网络连接或使用本地模型")
        return

    # 创建数据集
    train_dataset = IntentDataset(train_data, tokenizer, config.MAX_LENGTH)
    val_dataset = IntentDataset(val_data, tokenizer, config.MAX_LENGTH)

    train_loader = DataLoader(
        train_dataset,
        batch_size=config.BATCH_SIZE,
        shuffle=True,
        num_workers=0
    )
    val_loader = DataLoader(
        val_dataset,
        batch_size=config.BATCH_SIZE,
        shuffle=False,
        num_workers=0
    )

    # 创建模型
    print(f"\n创建模型...")
    model = IntentClassifier(
        model_name=config.MODEL_NAME,
        num_categories=config.NUM_CATEGORIES,
        num_subtypes=config.NUM_SUBTYPES,
        dropout=config.DROPOUT
    ).to(config.DEVICE)
    print(f"  [OK] 模型创建成功")

    # 优化器
    optimizer = AdamW(model.parameters(), lr=config.LEARNING_RATE, eps=1e-8)

    # 学习率调度
    total_steps = len(train_loader) * config.EPOCHS
    scheduler = get_linear_schedule_with_warmup(
        optimizer,
        num_warmup_steps=config.WARMUP_STEPS,
        num_training_steps=total_steps
    )

    # 训练循环
    print(f"\n开始训练...")
    print(f"总步数: {total_steps}")
    best_f1 = 0

    for epoch in range(config.EPOCHS):
        print(f"\n{'='*60}")
        print(f"Epoch {epoch + 1}/{config.EPOCHS}")
        print(f"{'='*60}")

        # 训练
        train_loss, train_cat_acc, train_sub_acc = train_epoch(
            model, train_loader, optimizer, scheduler, config.DEVICE
        )
        print(f"训练 - 损失: {train_loss:.4f}, "
              f"一级准确率: {train_cat_acc:.4f}, "
              f"二级准确率: {train_sub_acc:.4f}")

        # 评估
        val_metrics = evaluate(model, val_loader, config.DEVICE)
        print(f"验证 - 一级准确率: {val_metrics['category_accuracy']:.4f}, "
              f"F1: {val_metrics['category_f1']:.4f}")
        print(f"       二级准确率: {val_metrics['subtype_accuracy']:.4f}, "
              f"F1: {val_metrics['subtype_f1']:.4f}")

        # 保存最佳模型
        if val_metrics['category_f1'] > best_f1:
            best_f1 = val_metrics['category_f1']
            torch.save({
                'epoch': epoch,
                'model_state_dict': model.state_dict(),
                'optimizer_state_dict': optimizer.state_dict(),
                'category_f1': best_f1,
                'config': {
                    'model_name': config.MODEL_NAME,
                    'num_categories': config.NUM_CATEGORIES,
                    'num_subtypes': config.NUM_SUBTYPES
                }
            }, config.MODEL_SAVE_PATH)
            print(f"  [OK] 保存最佳模型 (F1: {best_f1:.4f})")

    print(f"\n{'='*60}")
    print(f"训练完成！最佳 F1: {best_f1:.4f}")
    print(f"模型保存至: {config.MODEL_SAVE_PATH}")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
