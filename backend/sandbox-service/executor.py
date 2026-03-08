import multiprocessing
import sys
import io
import json
import traceback
import pandas as pd
import numpy as np
from validator import validate_code


def _restricted_runner(code: str, data_json: str, result_queue: multiprocessing.Queue):
    """
    在隔离进程中运行代码的内部函数
    """

    output_buffer = io.StringIO()
    error_buffer = io.StringIO()

    # 重定向标准输出和错误
    sys.stdout = output_buffer
    sys.stderr = error_buffer

    # 准备全局变量
    local_scope = {}

    # 如果有输入数据，加载为 DataFrame
    if data_json:
        try:
            df = pd.read_json(io.StringIO(data_json), orient='records')
            local_scope['df'] = df
            print(f"Data Loaded: {len(df)} rows")
        except Exception as e:
            print(f"Error loading data: {e}")


    # 限制内建函数 (这里做一个简单的演示，生产环境需要更严格的构建)
    # 注意：真正安全的沙箱需要在此处构建一个非常干净的 __builtins__
    # 为了演示功能，我们暂时保留默认的 __builtins__ 但依赖 validator.py 进行静态检查拦截

    try:
        # 执行代码
        exec(code, local_scope)

        # 收集结果
        result = {
            'success': True,
            'stdout': output_buffer.getvalue(),
            'stderr': error_buffer.getvalue(),
        }
        result_queue.put(result)

    except Exception:
        # 捕获执行异常
        exc_type, exc_value, exc_traceback = sys.exc_info()
        # 隐藏部分 traceback 路径信息，只显示最后几行
        tb_lines = traceback.format_exception(exc_type, exc_value, exc_traceback)
        clean_tb = "".join(tb_lines[-5:]) # 只取最后5行

        result = {
            'success': False,
            'stdout': output_buffer.getvalue(),
            'stderr': error_buffer.getvalue() + "\n" + clean_tb,
        }
        result_queue.put(result)

def execute_code(code: str, data_json: str = None, timeout: int = 30) -> dict:
    """
    执行代码的主入口
    :param code: Python 代码
    :param data_json: JSON 格式的数据字符串 (List of Dicts)
    :param timeout: 超时时间 (秒)
    :return: 结果字典
    """

    # 1. 静态安全检查
    is_valid, errors = validate_code(code)
    if not is_valid:
        return {
            'success': False,
            'stdout': '',
            'stderr': "Security Validation Failed:\n" + "\n".join(errors),
        }

    # 2. 启动子进程执行
    result_queue = multiprocessing.Queue()
    process = multiprocessing.Process(
        target=_restricted_runner,
        args=(code, data_json, result_queue)
    )

    process.start()
    try:
        # 使用 get(timeout) 替代 process.join(timeout) 以防止死锁
        # 如果子进程写入大量数据（如Base64图片），导致管道满，
        # 父进程如果不读取而是在 join 等待，会造成死锁。
        import queue
        result = result_queue.get(timeout=timeout)
        process.join(1) # 等待子进程正常退出 cleanup
        return result
    except Exception: # 主要是 queue.Empty
        # 处理超时或无结果
        if process.is_alive():
            process.terminate()
            process.join()
            return {
                'success': False,
                'stdout': '',
                'stderr': f"Execution timed out after {timeout} seconds.",
            }
        else:
             return {
                'success': False,
                'stdout': '',
                'stderr': "Process execution failed silently (Crash or Kill).",
            }

if __name__ == "__main__":
    # 测试代码
    code = """
import numpy as np

print("Hello from Sandbox!")
print(f"Data shape: {df.shape}")

# 计算统计信息
result = df.describe()
print(result.to_json(orient='records', force_ascii=False))
"""
    data = '[{"a": 1, "b": 10}, {"a": 2, "b": 20}, {"a": 3, "b": 15}]'

    res = execute_code(code, data)
    print("Execution Result:")
    print(f"Success: {res['success']}")
    print(f"Stdout: {res['stdout']}")
    print(f"Stderr: {res['stderr']}")
