import json
import urllib.request
import urllib.parse
import urllib.error
import sys

# --- CONFIGURATION ---
INPUT_FILE = 'user_testcases.json'
OUTPUT_FILE = 'results.txt'
BASE_URL = "http://localhost:8081"

def run():
    # 1. Load the JSON File
    try:
        with open(INPUT_FILE, 'r') as f:
            test_cases = json.load(f)
    except FileNotFoundError:
        print(f"Error: '{INPUT_FILE}' not found.")
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"Error: Failed to parse JSON. {e}")
        sys.exit(1)

    # Ensure it's a dictionary
    if not isinstance(test_cases, dict):
        print("Error: Expected the JSON content to be a Dictionary (Key-Value pairs).")
        sys.exit(1)

    with open(OUTPUT_FILE, 'w') as out:
        out.write(f"--- TEST RUN RESULTS ---\n")
        out.write(f"Total Tests: {len(test_cases)}\n\n")
        print(f"Processing {len(test_cases)} tests from {INPUT_FILE}...")

        # 2. Iterate through named test cases
        for test_name, data in test_cases.items():
            
            # --- Determine Request Type ---
            # The Java server requires 'command' for POST. 
            # If it's missing, we assume it's a GET request (Retrieve).
            command = data.get("command")
            
            out.write(f"=== TEST: {test_name} ===\n")

            if command:
                # --- POST REQUEST (Create, Update, Delete) ---
                target_url = f"{BASE_URL}/edit"
                json_body = json.dumps(data)
                json_bytes = json_body.encode('utf-8')

                req = urllib.request.Request(
                    target_url, 
                    data=json_bytes, 
                    headers={'Content-Type': 'application/json'},
                    method="POST"
                )
                out.write(f"TYPE: POST ({command})\n")
                out.write(f"SENT: {json_body}\n")
            
            else:
                # --- GET REQUEST (Retrieve) ---
                # Retrieve requests in your file don't have a "command", just "id"
                user_id = data.get("id")
                
                # Handle edge case where ID might be missing or invalid type
                if user_id is None:
                    out.write(f"SKIPPED: No 'command' and no 'id' found.\n\n")
                    print(f"[{test_name}] SKIPPED")
                    continue
                
                # Construct URL (handles strings and ints automatically)
                params = urllib.parse.urlencode({'id': user_id})
                target_url = f"{BASE_URL}/retrieve?{params}"
                
                req = urllib.request.Request(target_url, method="GET")
                out.write(f"TYPE: GET\n")
                out.write(f"URL: {target_url}\n")

            # --- EXECUTE & LOG ---
            try:
                with urllib.request.urlopen(req) as response:
                    status = response.getcode()
                    response_body = response.read().decode('utf-8')
                    
                    out.write(f"STATUS: {status}\n")
                    out.write(f"RECEIVED: {response_body}\n")
                    print(f"[{test_name}] ✅ {status}")

            except urllib.error.HTTPError as e:
                # HTTP errors (400, 404, etc) are valid test results
                error_body = e.read().decode('utf-8')
                out.write(f"STATUS: {e.code}\n")
                out.write(f"ERROR BODY: {error_body}\n")
                print(f"[{test_name}] ❌ {e.code}")

            except Exception as e:
                # Connection refused, etc.
                out.write(f"STATUS: CONNECTION FAILED\n")
                out.write(f"EXCEPTION: {str(e)}\n")
                print(f"[{test_name}] 💥 Error")

            out.write("\n" + "-"*40 + "\n\n")

    print(f"\nDone! Full report saved to '{OUTPUT_FILE}'")

if __name__ == "__main__":
    run()