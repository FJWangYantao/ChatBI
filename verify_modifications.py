#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
验证脚本修改是否正确
检查关键改动是否已生效
"""

import os
import sys

def check_file_content(filepath, search_string, description):
    """检查文件中是否包含特定字符串"""
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()
            if search_string in content:
                print(f"[OK] {description}")
                return True
            else:
                print(f"[FAIL] {description}")
                return False
    except Exception as e:
        print(f"[FAIL] Unable to read file: {filepath} - {e}")
        return False

def main():
    print("\n" + "="*70)
    print("Script Modification Verification Checklist")
    print("="*70)
    
    base_path = os.path.dirname(os.path.abspath(__file__))
    scripts_path = os.path.join(base_path, 'scripts')
    
    # 检查新接口调用
    print("\n[API Interface Verification]")
    
    checks = [
        (
            os.path.join(scripts_path, 'extract_and_test_questions.py'),
            'f"{BACKEND_BASE_URL}/chat/message"',
            'extract_and_test_questions.py uses new interface /api/chat/message'
        ),
        (
            os.path.join(scripts_path, 'extract_and_test_questions.py'),
            '"conversationId": None',
            'extract_and_test_questions.py includes conversationId parameter'
        ),
        (
            os.path.join(scripts_path, 'test_text2sql_accuracy.py'),
            'f"{BACKEND_BASE_URL}/chat/message"',
            'test_text2sql_accuracy.py uses new interface /api/chat/message'
        ),
        (
            os.path.join(scripts_path, 'test_text2sql_accuracy.py'),
            '"intent_info": data.get("intentInfo")',
            'test_text2sql_accuracy.py captures intent information'
        ),
        (
            os.path.join(scripts_path, 'test_text2sql_accuracy.py'),
            'intent.get(\'categoryCn\')',
            'test_text2sql_accuracy.py displays intent recognition results'
        ),
    ]
    
    success_count = 0
    for filepath, search_string, description in checks:
        if os.path.exists(filepath):
            if check_file_content(filepath, search_string, description):
                success_count += 1
        else:
            print(f"[FAIL] File not found: {filepath}")
    
    # 检查新文件是否存在
    print("\n[New Files Verification]")
    
    new_files = [
        (os.path.join(scripts_path, 'quick_test_ner_api.py'), 'Quick test script'),
        (os.path.join(base_path, 'SCRIPT_UPDATE_GUIDE.md'), 'Modification guide document'),
        (os.path.join(base_path, 'MODIFICATION_SUMMARY.md'), 'Modification summary document'),
        (os.path.join(base_path, 'FINAL_VERIFICATION.md'), 'Final verification document'),
    ]
    
    for filepath, description in new_files:
        if os.path.exists(filepath):
            print(f"[OK] {description} created")
            success_count += 1
        else:
            print(f"[FAIL] {description} not found: {filepath}")
    
    # 总结
    total_checks = len(checks) + len(new_files)
    print(f"\n{'='*70}")
    print(f"Verification Result: {success_count}/{total_checks} passed ({100*success_count//total_checks}%)")
    print('='*70)
    
    if success_count == total_checks:
        print("\n[SUCCESS] All checks passed!")
        print("\nNext steps:")
        print("  1. Run quick test: python scripts/quick_test_ner_api.py")
        print("  2. Check backend logs for 'NER identified' messages")
        print("  3. Run complete test scripts to verify functionality")
        return 0
    else:
        print(f"\n[WARNING] {total_checks - success_count} checks failed")
        print("  Please check the items marked as [FAIL] above")
        return 1

if __name__ == "__main__":
    sys.exit(main())
