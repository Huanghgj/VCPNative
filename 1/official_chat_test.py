import json
import xxhash
import requests

def calculate_cch(body_bytes):
    h = xxhash.xxh64(body_bytes, seed=0x6E52736AC806831E).intdigest()
    return f"{h & 0xFFFFF:05x}"

def main():
    # 尝试使用 HTTP 协议绕过 SSL 握手问题
    url = "http://tmp.linux.do/cch"
    proxies = {"http": "http://127.0.0.1:7897", "https": "http://127.0.0.1:7897"}

    # 1. 构造初始 payload (cch=00000)
    # 严格按照你提供的“最正确注入方式”：billing block 是 system 数组的第 0 个元素
    payload = {
        "model": "claude-sonnet-4-6",
        "max_tokens": 20096,
        "messages": [
            {
                "role": "user",
                "content": "你好，请进行深度思考并回答：为什么 1+1 在某些情况下不等于 2？"
            }
        ],
        "system": [
            {
                "type": "text",
                "text": "x-anthropic-billing-header: cc_version=2.1.91.527; cc_entrypoint=cli; cch=00000;"
            },
            {
                "type": "text",
                "text": "你是一个专业的编程助手，请使用中文回答。"
            }
        ],
        "thinking": {
            "type": "enabled",
            "budget_tokens": 2000
        },
        "stream": True
    }

    # 2. 计算 CCH (使用紧凑格式 JSON)
    body_json = json.dumps(payload, separators=(',', ':'))
    real_cch = calculate_cch(body_json.encode('utf-8'))
    
    # 3. 替换真实 CCH
    payload["system"][0]["text"] = payload["system"][0]["text"].replace("00000", real_cch)
    print(f"Calculated CCH: {real_cch}")

    # 4. 官方 Headers (补全 beta 标识)
    headers = {
        "Content-Type": "application/json",
        "anthropic-version": "2023-06-01",
        "anthropic-beta": "claude-code-20250219,prompt-caching-2024-07-31,interleaved-thinking-2025-05-14,code-execution-2025-05-22",
        "User-Agent": "claude-cli/2.1.91 (external, cli)",
        "x-app": "cli"
    }

    print(f"Sending request to {url}...")
    try:
        response = requests.post(
            url,
            headers=headers,
            json=payload,
            proxies=proxies,
            stream=True,
            timeout=120,
            verify=False
        )
        
        print(f"Status Code: {response.status_code}")
        
        for line in response.iter_lines():
            if line:
                line_str = line.decode('utf-8')
                if line_str.startswith("data: "):
                    try:
                        data = json.loads(line_str[6:])
                        if data["type"] == "content_block_delta":
                            delta = data["delta"]
                            if delta["type"] == "thinking_delta":
                                print(f"[Thinking] {delta['thinking']}", end="", flush=True)
                            elif delta["type"] == "text_delta":
                                print(delta["text"], end="", flush=True)
                    except:
                        pass
                elif '"cch"' in line_str:
                    print(f"\n[Server CCH Update] {line_str}")
                
    except Exception as e:
        print(f"\nError: {e}")

if __name__ == "__main__":
    main()