with open('old_scm.kt', 'r') as f:
    old_content = f.read()

# Extract sendStatus (lines 202-211 approx, until next docstring)
import re
send_status_match = re.search(r'    private fun sendStatus\(context: Context, status: String\) \{.*?(?=    /\*\*|    private fun testServers)', old_content, re.DOTALL)
send_status_code = send_status_match.group(0)

# Extract loadCustomSubs
load_subs_match = re.search(r'    private fun loadCustomSubs\(\): List<CustomSubData> \{.*?(?=    private fun sendStatus)', old_content, re.DOTALL)
load_subs_code = load_subs_match.group(0)

# Extract filterServers
filter_servers_match = re.search(r'    private fun filterServers\(allServers: List<String>, excludeGuid: String\? = null\): List<Pair<String, ProfileItem>> \{.*?(?=    /\*\*|    suspend fun switchServer)', old_content, re.DOTALL)
filter_servers_code = filter_servers_match.group(0)

with open('V2rayNG/app/src/main/java/com/kiktor/v2whitelist/handler/SmartConnectManager.kt', 'r') as f:
    new_content = f.read()

# Insert them back inside object SmartConnectManager {
injection = f"\n{send_status_code}\n{load_subs_code}\n{filter_servers_code}\n"
new_content = new_content.replace("object SmartConnectManager {", "object SmartConnectManager {" + injection)

with open('V2rayNG/app/src/main/java/com/kiktor/v2whitelist/handler/SmartConnectManager.kt', 'w') as f:
    f.write(new_content)

# Fix NodeTesterManager.kt
with open('V2rayNG/app/src/main/java/com/kiktor/v2whitelist/handler/NodeTesterManager.kt', 'r') as f:
    ntm_content = f.read()

bad_line = 'GeekModeLogger.log("NodeTester", "Chunk testing timed out + ": " + proceeding with partial results (${resultsList.size})")'
good_line = 'GeekModeLogger.log("NodeTester", "Chunk testing timed out: proceeding with partial results (${resultsList.size})")'
ntm_content = ntm_content.replace(bad_line, good_line)

with open('V2rayNG/app/src/main/java/com/kiktor/v2whitelist/handler/NodeTesterManager.kt', 'w') as f:
    f.write(ntm_content)

# Fix GeekModeBottomSheetFragment.kt
with open('V2rayNG/app/src/main/java/com/kiktor/v2whitelist/ui/GeekModeBottomSheetFragment.kt', 'r') as f:
    frag_content = f.read()

frag_content = frag_content.replace('binding.tvLogs.text = logsList.joinToString("\\n")\n")', 'binding.tvLogs.text = logsList.joinToString("\\n")')
frag_content = frag_content.replace('binding.tvLogs.text = logsList.joinToString("\n")', 'binding.tvLogs.text = logsList.joinToString("\\n")')

with open('V2rayNG/app/src/main/java/com/kiktor/v2whitelist/ui/GeekModeBottomSheetFragment.kt', 'w') as f:
    f.write(frag_content)

