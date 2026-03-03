import multiprocessing
import sys
import io
import json
import traceback
import base64
import pandas as pd
import numpy as np
from validator import validate_code
from chart_extractor import extract_chart_data


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

    
    # 在子进程中动态导入 matplotlib，避免 Windows spawn 问题
    try:
        import matplotlib
        matplotlib.use('Agg', force=True)
        import matplotlib.pyplot as plt
        local_scope['plt'] = plt

        # 尝试导入 seaborn（可选）
        try:
            import seaborn as sns
            local_scope['sns'] = sns
        except ImportError:
            pass  # seaborn 不是必需的

        # 清除之前的 figures (虽是新进程，但习惯上保持干净)
        plt.close('all')
    except Exception as e:
        error_buffer.write(f"Warning: Failed to initialize Matplotlib: {e}\n")
    
    # 限制内建函数 (这里做一个简单的演示，生产环境需要更严格的构建)
    # 注意：真正安全的沙箱需要在此处构建一个非常干净的 __builtins__
    # 为了演示功能，我们暂时保留默认的 __builtins__ 但依赖 validator.py 进行静态检查拦截
    
    try:
        # 2. 执行代码
        exec(code, local_scope)
        
        # 3. 捕获图表（方式一：内存中的 matplotlib figure）
        images = []
        chart_data = None
        if plt.get_fignums():
            # 先提取图表数据（用于交互式渲染）
            try:
                chart_data = extract_chart_data(plt)
            except Exception as e:
                error_buffer.write(f"Warning: Failed to extract chart data: {e}\n")

            # 再生成 PNG（作为降级方案）
            for i in plt.get_fignums():
                fig = plt.figure(i)
                buf = io.BytesIO()
                fig.savefig(buf, format='png', bbox_inches='tight')
                buf.seek(0)
                img_base64 = base64.b64encode(buf.read()).decode('utf-8')
                images.append(img_base64)
                plt.close(fig)

        # 3b. 捕获图表（方式二：检查磁盘上的 output.png）
        # AI 生成的代码可能会调用 plt.savefig('output.png')，导致 figure 被写入磁盘
        # 只有内存中没有捕获到图片时才读磁盘，避免重复
        import os
        output_file = 'output.png'
        if os.path.exists(output_file):
            try:
                with open(output_file, 'rb') as f:
                    img_base64 = base64.b64encode(f.read()).decode('utf-8')
                    if img_base64 not in images:  # 去重，防止内存和磁盘都有时重复
                        images.append(img_base64)
                os.remove(output_file)  # 清理临时文件
            except Exception as e:
                error_buffer.write(f"Warning: Failed to read output.png: {e}\n")

        # 4. 收集结果
        result = {
            'success': True,
            'stdout': output_buffer.getvalue(),
            'stderr': error_buffer.getvalue(),
            'images': images,
            'chart_data': chart_data  # 新增：图表数据
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
            'images': [],
            'chart_data': None
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
            'images': [],
            'chart_data': None
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
                'images': [],
                'chart_data': None
            }
        else:
             return {
                'success': False,
                'stdout': '',
                'stderr': "Process execution failed silently (Crash or Kill).",
                'images': [],
                'chart_data': None
            }

if __name__ == "__main__":
    # 测试代码
    code = """
import numpy as np
import matplotlib.pyplot as plt

print("Hello from Sandbox!")
print(f"Data shape: {df.shape}")

# 画图
plt.figure()
plt.plot(df['a'], df['b'])
plt.title("Test Plot")
"""
    data = '[{"a": 1, "b": 10}, {"a": 2, "b": 20}, {"a": 3, "b": 15}]'
    
    res = execute_code(code, data)
    print("Execution Result:")
    print(f"Success: {res['success']}")
    print(f"Stdout: {res['stdout']}")
    print(f"Stderr: {res['stderr']}")
    print(f"Images count: {len(res['images'])}")
