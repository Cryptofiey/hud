
import sys

def count_braces(filename):
    with open(filename, 'r') as f:
        content = f.read()
    
    count = 0
    lines = content.split('\n')
    for i, line in enumerate(lines):
        for char in line:
            if char == '{':
                count += 1
            elif char == '}':
                count -= 1
        if count < 0:
            print(f"Negative brace count at line {i+1}: {line}")
            return
    
    if count != 0:
        print(f"Brace mismatch! Final count: {count}")
    else:
        print("Braces are balanced.")

if __name__ == "__main__":
    count_braces(sys.argv[1])
