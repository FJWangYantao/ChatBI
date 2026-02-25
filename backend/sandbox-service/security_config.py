# 安全配置 - 白名单定义

# 允许导入的模块
SAFE_MODULES = {
    'math',
    'datetime',
    'warnings',  # 常用于抑制 matplotlib/pandas 的字体等警告，安全
    'pandas',
    'numpy',
    'json',
    're',
    'random',
    'collections',
    'itertools',
    'functools',
    'matplotlib',
    'matplotlib.pyplot',
    'seaborn',
    'sklearn',
    'scipy',
    'scipy.stats',
    'io',
    'base64'
}

# 允许使用的内置函数/类型
SAFE_BUILTINS = {
    'print',
    'len',
    'range',
    'list',
    'dict',
    'set',
    'tuple',
    'str',
    'int',
    'float',
    'bool',
    'complex',
    'abs',
    'min',
    'max',
    'sum',
    'round',
    'divmod',
    'pow',
    'enumerate',
    'zip',
    'map',
    'filter',
    'sorted',
    'reversed',
    'slice',
    'any',
    'all',
    'isinstance',
    'issubclass',
    'hasattr', # 需要小心使用，但在数据分析中常用
    'getattr', # 同上
    'Exception',
    'ValueError',
    'TypeError',
    'KeyError',
    'IndexError',
    'AttributeError',
    'None',
    'True',
    'False'
}

# 危险的内置函数（明确禁止，用于AST检查）
DANGEROUS_FUNCTIONS = {
    'eval',
    'exec',
    'compile',
    'open',
    'input',
    '__import__',
    'globals',
    'locals',
    'exit',
    'quit',
    'help',
    'dir',
    'vars',
    'memoryview',
    'breakpoint'
}

# 危险的属性/方法（AST检查）
DANGEROUS_ATTRIBUTES = {
    '__builtins__',
    '__globals__',
    '__code__',
    '__closure__',
    '__func__',
    '__module__',
    '__bases__',
    '__mro__',
    '__subclasses__',
    'os',
    'sys',
    'subprocess',
}
