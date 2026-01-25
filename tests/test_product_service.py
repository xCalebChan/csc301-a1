import json
import urllib.request
import urllib.error
import sys

# --- CONFIGURATION ---
INPUT_FILE = 'product_testcases.json'
OUTPUT_FILE = 'product_results.txt'
BASE_URL = "http://localhost:8081"

def run():
    # 1. Load the JSON File
    try:
        with open(INPUT_FILE, 'r', encoding='utf-8') as f:
            test_cases = json.load(f)
    except FileNotFoundError:
        print(f"Error: '{INPUT_FILE}' not found.")
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"Error: Failed to parse JSON. {e}")
        sys.exit(1)

    # Ensure it's a dictionary: { "testName": { ... } }
    if not isinstance(test_cases, dict):
        print("Error: Expected the JSON content to be a Dictionary (Key-Value pairs).")
        sys.exit(1)

    with open(OUTPUT_FILE, 'w', encoding='utf-8') as out:
        out.write(f"--- PRODUCT SERVICE TEST RUN RESULTS ---\n")
        out.write(f"Base URL: {BASE_URL}\n")
        out.write(f"Total Tests: {len(test_cases)}\n\n")
        print(f"Processing {len(test_cases)} tests from {INPUT_FILE}...")

        for test_name, data in test_cases.items():
            if not isinstance(data, dict):
                out.write(f"=== TEST: {test_name} ===\n")
                out.write("SKIPPED: Test case value is not an object/dict.\n\n")
                print(f"[{test_name}] SKIPPED (not an object)")
                continue

            command = data.get("command")

            out.write(f"=== TEST: {test_name} ===\n")

            # --- Build request ---
            if command:
                # POST /product  (create/update/delete)
                target_url = f"{BASE_URL}/product"

                json_body = json.dumps(data)
                json_bytes = json_body.encode('utf-8')

                req = urllib.request.Request(
                    target_url,
                    data=json_bytes,
                    headers={'Content-Type': 'application/json'},
                    method="POST"
                )

                out.write(f"TYPE: POST ({command})\n")
                out.write(f"URL: {target_url}\n")
                out.write(f"SENT: {json_body}\n")

            else:
                # GET /product/<id>
                product_id = data.get("id")

                if product_id is None:
                    out.write("SKIPPED: No 'command' and no 'id' found.\n\n")
                    print(f"[{test_name}] SKIPPED")
                    continue

                # Make sure we can safely put it in the path
                product_id_str = str(product_id).strip()
                if product_id_str == "":
                    out.write("SKIPPED: 'id' is empty/blank.\n\n")
                    print(f"[{test_name}] SKIPPED")
                    continue

                target_url = f"{BASE_URL}/product/{product_id_str}"
                req = urllib.request.Request(target_url, method="GET")

                out.write("TYPE: GET\n")
                out.write(f"URL: {target_url}\n")

            # --- Execute & log ---
            try:
                with urllib.request.urlopen(req) as response:
                    status = response.getcode()
                    response_body = response.read().decode('utf-8', errors='replace')

                    out.write(f"STATUS: {status}\n")
                    out.write(f"RECEIVED: {response_body}\n")
                    print(f"[{test_name}] ✅ {status}")

            except urllib.error.HTTPError as e:
                error_body = e.read().decode('utf-8', errors='replace')
                out.write(f"STATUS: {e.code}\n")
                out.write(f"ERROR BODY: {error_body}\n")
                print(f"[{test_name}] ❌ {e.code}")

            except Exception as e:
                out.write("STATUS: CONNECTION FAILED\n")
                out.write(f"EXCEPTION: {str(e)}\n")
                print(f"[{test_name}] 💥 Error: {e}")

            out.write("\n" + "-" * 40 + "\n\n")

    print(f"\nDone! Full report saved to '{OUTPUT_FILE}'")


if __name__ == "__main__":
    run()
