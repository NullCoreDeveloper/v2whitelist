from bs4 import BeautifulSoup
import collections

with open('lint-results-fdroidDebug.html', 'r', encoding='utf-8') as f:
    soup = BeautifulSoup(f.read(), 'html.parser')

issues = collections.defaultdict(int)
errors = collections.defaultdict(list)

# Android lint HTML report typically has sections for each issue type
# Let's just grab all the headers or issue titles
for section in soup.find_all('div', class_='issue'):
    title = section.find('div', class_='title')
    if title:
        issues[title.text.strip()] += 1

# Or it might be structured as a material design card
for card in soup.find_all('div', class_='mdl-card'):
    title_elem = card.find('h4', class_='mdl-card__title-text')
    if title_elem:
        title = title_elem.text.strip()
        issues[title] += 1
        
        # Check if it's an error
        content = card.find('div', class_='mdl-card__supporting-text')
        if content and 'Error' in content.text:
            errors[title].append(content.text.strip()[:200]) # save snippet

print("--- LINT SUMMARY ---")
if not issues:
    print("Could not parse with typical selectors. Trying to find issue names...")
    # fallback
    for a in soup.find_all('a', name=True):
        if 'issue' in a.get('name', '').lower() or 'error' in a.get('name', '').lower():
            print(f"Found anchor: {a.get('name')}")
            parent = a.find_parent('section')
            if parent:
                print(parent.text[:200].replace('\n', ' '))
else:
    for title, count in sorted(issues.items(), key=lambda x: x[1], reverse=True):
        print(f"{count}x: {title}")
        if title in errors:
            print(f"  Example error: {errors[title][0]}")
