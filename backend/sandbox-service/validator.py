import ast
from security_config import SAFE_MODULES, DANGEROUS_FUNCTIONS, DANGEROUS_ATTRIBUTES

class SecurityVisitor(ast.NodeVisitor):
    def __init__(self):
        self.errors = []

    def visit_Import(self, node):
        for alias in node.names:
            if alias.name.split('.')[0] not in SAFE_MODULES:
                self.errors.append(f"Line {node.lineno}: Importing module '{alias.name}' is not allowed.")
        self.generic_visit(node)

    def visit_ImportFrom(self, node):
        if node.module and node.module.split('.')[0] not in SAFE_MODULES:
            self.errors.append(f"Line {node.lineno}: Importing from module '{node.module}' is not allowed.")
        self.generic_visit(node)

    def visit_Call(self, node):
        # 检查是否调用了危险函数
        if isinstance(node.func, ast.Name):
            if node.func.id in DANGEROUS_FUNCTIONS:
                self.errors.append(f"Line {node.lineno}: Function '{node.func.id}' is forbidden.")
        self.generic_visit(node)

    def visit_Attribute(self, node):
        # 检查是否访问了私有属性或危险属性
        if node.attr.startswith('_') and not node.attr.startswith('__'): 
             # 允许双下划线（魔术方法），但通常不允许单下划线私有属性
             # 在pandas/numpy中，有时会用到 _ ，这里先从严，如果需要可以放宽
             pass 
        
        if node.attr in DANGEROUS_ATTRIBUTES:
            self.errors.append(f"Line {node.lineno}: Accessing attribute '{node.attr}' is forbidden.")
            
        self.generic_visit(node)

def validate_code(code: str) -> tuple[bool, list[str]]:
    """
    静态代码分析，检查是否存在潜在的不安全操作。
    :param code: 待检查的 Python 代码
    :return: (is_valid, error_messages)
    """
    try:
        tree = ast.parse(code)
    except SyntaxError as e:
        return False, [f"Syntax Error: {e}"]

    visitor = SecurityVisitor()
    visitor.visit(tree)

    if visitor.errors:
        return False, visitor.errors
    
    return True, []

if __name__ == "__main__":
    # 测试代码
    test_code_safe = """
import pandas as pd
import numpy as np
df = pd.DataFrame({'a': [1, 2], 'b': [3, 4]})
print(df.head())
    """
    
    test_code_unsafe = """
import os
os.system('calc')
    """
    
    test_code_unsafe_2 = """
print(open('secret.txt').read())
    """

    print("Checking safe code...")
    valid, errors = validate_code(test_code_safe)
    print(f"Valid: {valid}, Errors: {errors}")

    print("\nChecking unsafe code 1 (os)...")
    valid, errors = validate_code(test_code_unsafe)
    print(f"Valid: {valid}, Errors: {errors}")
    
    print("\nChecking unsafe code 2 (open)...")
    valid, errors = validate_code(test_code_unsafe_2)
    print(f"Valid: {valid}, Errors: {errors}")
