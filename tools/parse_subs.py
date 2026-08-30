import urllib.request
import urllib.parse
import re

URLS = {
    "rkp_whitelist": ["https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/main/whitelist.txt"],
    "rkp_blacklist": ["https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/main/blacklist.txt"]
}

REGEXES = {
    "rkp_whitelist": r"(🌐)\s*Неизвестно",
    "rkp_blacklist": r"(🌐)\s*Неизвестно"
}

def extract_flag(text):
    match = re.search(r'([\U0001f1e6-\U0001f1ff]{2})', text)
    if match:
        return match.group(1)
    return None

def apply_regex(text, regex_str):
    if not regex_str:
        return None
    match = re.search(regex_str, text)
    if match and len(match.groups()) >= 1:
        return match.group(1).strip()
    return None

def main():
    for name, urls in URLS.items():
        print(f"\n======================\nAnalyzing: {name} with Regex: {REGEXES[name]}")
        total_servers = 0
        unmatched_remarks = []
        flags_found = {}
        
        for url in urls:
            req = urllib.request.Request(url, headers={'User-Agent': 'v2rayNG/1.8.12'})
            try:
                response = urllib.request.urlopen(req, timeout=10)
                content = response.read().decode('utf-8').splitlines()
            except Exception as e:
                print(f"Failed to download {url}: {e}")
                continue
                
            for line in content:
                line = line.strip()
                if not line:
                    continue
                if line.startswith("vless://") or line.startswith("vmess://") or line.startswith("trojan://"):
                    total_servers += 1
                    parts = line.split('#')
                    if len(parts) > 1:
                        remark = urllib.parse.unquote(parts[1])
                    else:
                        remark = "none"
                    
                    tag = apply_regex(remark, REGEXES[name])
                    
                    if not tag:
                        tag = extract_flag(remark)
                        
                    if not tag:
                        tag = "🌐"
                        unmatched_remarks.append(remark)
                        
                    flags_found[tag] = flags_found.get(tag, 0) + 1

        print(f"Total servers: {total_servers}")
        sorted_flags = sorted(flags_found.items(), key=lambda x: x[1], reverse=True)
        print("Groups found:", ", ".join([f"{f}: {c}" for f, c in sorted_flags[:10]]))
        
        print(f"Servers without flag/regex match: {len(unmatched_remarks)}")
        if unmatched_remarks:
            unique_unmatched = list(set(unmatched_remarks))
            for r in unique_unmatched[:5]:
                print(f" - {r}")

if __name__ == "__main__":
    main()
