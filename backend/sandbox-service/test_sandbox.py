
import requests
import json
import time

url = "http://localhost:8003/execute"
code = """
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

# Create sample data
df = pd.DataFrame({
    'x': range(10),
    'y': np.random.randn(10)
})

print("DataFrame Head:")
print(df.head())

plt.figure()
plt.plot(df['x'], df['y'])
plt.title("Random Plot")
# The figure will be captured by the sandbox
"""

payload = {                                                                                                                                                                                                                                     
    "code": code,
    "timeout": 30
}

print(f"Sending request to {url}...")
try:
    response = requests.post(url, json=payload)
    print(f"Status Code: {response.status_code}")
    if response.status_code == 200:
        res = response.json()
        print("Success:", res['success'])
        print("Stdout:\n", res['stdout'])
        print("Stderr:\n", res['stderr'])
        print(f"Images: {len(res['images'])} images captured")
    else:
        print("Error Response:", response.text)
except Exception as e:
    print(f"Connection failed: {e}")
