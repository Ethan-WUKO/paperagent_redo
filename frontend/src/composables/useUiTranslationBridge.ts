import { nextTick, onBeforeUnmount, onMounted, watch, type Ref } from 'vue';
import { useI18n, type AppLocale } from '@/composables/useI18n';

type TranslationPair = readonly [english: string, chinese: string];

// Last-mile translations for legacy page surfaces that have not yet moved to keyed i18n.
// Exact matching keeps filenames, user messages, paper content, and backend data untouched.
const UI_TRANSLATIONS: readonly TranslationPair[] = [
  ['Refresh', '刷新'],
  ['Sync', '同步'],
  ['Search', '搜索'],
  ['Clear', '清除'],
  ['Cancel', '取消'],
  ['Save', '保存'],
  ['Delete', '删除'],
  ['Preview', '预览'],
  ['Download', '下载'],
  ['Submit', '提交'],
  ['Skip', '跳过'],
  ['Confirm', '确认'],
  ['Expand', '展开'],
  ['Collapse', '收起'],
  ['Hide', '隐藏'],
  ['Loading...', '加载中...'],
  ['Ready', '就绪'],
  ['Required', '必填'],
  ['Pending', '等待中'],
  ['Running', '运行中'],
  ['Paused', '已暂停'],
  ['Completed', '已完成'],
  ['Failed', '失败'],
  ['Cancelled', '已取消'],
  ['Public', '公开'],
  ['Private', '私有'],
  ['Actions', '操作'],
  ['Status', '状态'],
  ['Type', '类型'],
  ['Size', '大小'],
  ['Updated', '更新时间'],
  ['Filename', '文件名'],
  ['Visibility', '可见性'],
  ['Project', '项目'],
  ['Owner', '负责人'],
  ['Mode', '模式'],
  ['Progress', '进度'],
  ['Stage', '阶段'],
  ['Sections', '章节'],
  ['Attempts', '尝试次数'],
  ['Started', '开始时间'],
  ['Evidence', '证据'],
  ['Suggestion', '建议'],
  ['Severity', '严重程度'],
  ['State', '状态'],
  ['History', '历史记录'],
  ['Workflow', '工作流'],
  ['Upload', '上传'],
  ['Parse', '解析'],
  ['Export', '导出'],
  ['Support', '支持'],
  ['Recommendations', '建议'],
  ['Task Status', '任务状态'],
  ['No task', '暂无任务'],
  ['New manuscript polish', '新论文润色'],
  ['Full paper polish', '整篇论文润色'],
  ['Current researcher', '当前研究者'],
  ['Overall progress', '整体进度'],
  ['Current stage', '当前阶段'],
  ['Task error', '任务错误'],
  ['Structure Check', '结构确认'],
  ['Research Profile', '研究画像'],
  ['Literature Retrieval', '文献检索'],
  ['Critique', '审查'],
  ['Section Polish', '分章润色'],
  ['Global Review', '全文审查'],
  ['Revision Suggestions', '润色建议'],
  ['Evidence Snippets', '证据片段'],
  ['Literature Support', '文献支持'],
  ['Export Artifacts', '导出产物'],
  ['Live Task Events', '实时任务事件'],
  ['Review Workspace', '审查工作区'],
  ['Clarifications', '确认项'],
  ['Diff + cite patch', '差异与引用补丁'],
  ['New references', '新增参考文献'],
  ['Existing references reused', '复用已有参考文献'],
  ['Citation locations updated', '已更新引用位置'],
  ['Existing locations verified', '已核验已有位置'],
  ['View source', '查看来源'],
  ['Refresh tasks', '刷新任务'],
  ['Reconnect SSE', '重连 SSE'],
  ['Pause', '暂停'],
  ['Resume', '继续'],
  ['Stop', '停止'],
  ['Expand revision suggestions', '展开润色建议'],
  ['Collapse revision suggestions', '收起润色建议'],
  ['Expand evidence snippets', '展开证据片段'],
  ['Collapse evidence snippets', '收起证据片段'],
  ['No revision suggestions yet. Run a workflow or open a completed task.', '暂无润色建议，请运行工作流或打开已完成任务。'],
  ['No evidence snippets yet.', '暂无证据片段。'],
  ['No literature support yet.', '暂无文献支持。'],
  ['No export artifacts yet.', '暂无导出产物。'],
  ['No live events yet.', '暂无实时事件。'],
  ['No history yet.', '暂无历史记录。'],
  ['Input required', '需要输入'],
  ['No clarifications yet.', '暂无确认项。'],
  ['No section roles yet.', '暂无章节角色。'],
  ['No preview results yet.', '暂无预览结果。'],
  ['No bibliography report yet.', '暂无参考文献报告。'],
  ['Structure confirmation', '结构确认'],
  ['Keep detected structure', '保留识别结构'],
  ['Loading structure confirmation items...', '正在加载结构确认项...'],
  ['Ready to start a new paper polish task.', '可以开始新的论文润色任务。'],

  ['Knowledge Base', '知识库'],
  ['Manage private research documents, parsing status, retrieval visibility, and previewable text assets.', '管理私有研究文档、解析状态、检索可见性和可预览文本。'],
  ['Search Debug', '检索调试'],
  ['Total documents', '文档总数'],
  ['Ready for RAG', '可用于 RAG'],
  ['Processing', '处理中'],
  ['Storage used', '已用存储'],
  ['Upload Documents', '上传文档'],
  ['Make this document public in the workspace', '在工作区中公开此文档'],
  ['Documents', '文档'],
  ['No knowledge base documents yet.', '知识库中暂无文档。'],
  ['Demo seed', '演示数据'],
  ['Parsed Text Preview', '解析文本预览'],
  ['Copy', '复制'],
  ['File size', '文件大小'],
  ['Preview chunks', '预览分块数'],
  ['Max chars', '最大字符数'],
  ['Truncated preview', '预览已截断'],
  ['No parsed text yet', '暂无解析文本'],
  ['Select Preview on a document to inspect parsed text.', '点击文档的“预览”查看解析文本。'],
  ['Please choose a file first.', '请先选择文件。'],
  ['Upload complete. The document is processing.', '上传完成，文档正在处理。'],
  ['Upload failed.', '上传失败。'],
  ['Failed to load knowledge documents.', '加载知识库文档失败。'],
  ['Document deleted.', '文档已删除。'],
  ['Delete failed.', '删除失败。'],
  ['Failed to load preview.', '加载预览失败。'],
  ['Preview content copied.', '预览内容已复制。'],
  ['Copy failed. Please select the text manually.', '复制失败，请手动选择文本。'],

  ['Knowledge Search Debug', '知识库检索调试'],
  ['Inspect retrieval quality, score bands, selected chunks, and RAG visibility before shipping answers to users.', '在生成回答前检查检索质量、分数区间、所选分块和 RAG 可见性。'],
  ['Sample Query', '示例查询'],
  ['Retrieval Console', '检索控制台'],
  ['Query', '查询'],
  ['Example: What is the weekly lab meeting time?', '例如：每周实验室会议在什么时间？'],
  ['Document Scope', '文档范围'],
  ['Private + permitted public', '私有文档与获准公开文档'],
  ['Embedding', '向量模型'],
  ['Configured backend model', '后端已配置模型'],
  ['Action', '操作'],
  ['Search latency', '检索耗时'],
  ['Retrieved chunks', '召回分块'],
  ['Last run', '上次运行'],
  ['Results', '结果'],
  ['Run a query to inspect retrieval results.', '运行查询以检查检索结果。'],
  ['Rank', '排名'],
  ['File / Chunk', '文件 / 分块'],
  ['Score', '分数'],
  ['Band', '区间'],
  ['Snippet', '片段'],
  ['Diagnostics', '诊断'],
  ['Recall Status', '召回状态'],
  ['Low-confidence results detected', '检测到低置信度结果'],
  ['Retrieval looks usable', '检索结果可用'],
  ['Top-K Score Distribution', 'Top-K 分数分布'],
  ['Average score', '平均分数'],
  ['High score chunks', '高分分块'],
  ['No cross-user leakage', '无跨用户数据泄漏'],
  ['Pass', '通过'],
  ['Requested Top K', '请求的 Top K'],
  ['Selected Result Inspection', '所选结果检查'],
  ['Select a result row to inspect it.', '选择一条结果进行检查。'],
  ['Please enter a query.', '请输入查询内容。'],
  ['No retrieval results found.', '未找到检索结果。'],
  ['Search failed.', '检索失败。'],

  ['Settings', '设置'],
  ['Configure model providers, agent behavior, MCP permissions, skills, and credentials without exposing secrets.', '配置模型提供商、Agent 行为、MCP 权限、Skills 和凭据，并保护敏感信息。'],
  ['Save settings', '保存设置'],
  ['Model Providers', '模型提供商'],
  ['Reasoning and drafting provider', '推理与写作提供商'],
  ['Alternate provider for evaluation', '用于评测的备用提供商'],
  ['Model name', '模型名称'],
  ['Available models', '可用模型'],
  ['API Key', 'API 密钥'],
  ['Leave blank to keep current key', '留空以保留当前密钥'],
  ['Default provider', '默认提供商'],
  ['Temperature', '温度'],
  ['Max plan steps', '最大计划步骤'],
  ['Web Search Provider', '网页搜索提供商'],
  ['Tavily / formal web search', 'Tavily / 正式网页搜索'],
  ['Configured through backend environment variables such as WEB_SEARCH_PROVIDER and TAVILY_API_KEY.', '通过 WEB_SEARCH_PROVIDER、TAVILY_API_KEY 等后端环境变量配置。'],
  ['Credit-saving defaults enabled', '已启用节省额度的默认配置'],
  ['Search depth', '搜索深度'],
  ['basic by default', '默认 basic'],
  ['Cache TTL', '缓存有效期'],
  ['15 min default', '默认 15 分钟'],
  ['Max results', '最大结果数'],
  ['8 default', '默认 8 条'],
  ['Agent and MCP / Tools', 'Agent 与 MCP / 工具'],
  ['Knowledge Base RAG', '知识库 RAG'],
  ['New sessions can use private retrieval by default.', '新会话默认可以使用私有检索。'],
  ['GitHub MCP', 'GitHub MCP'],
  ['Repository access for code and documentation workflows.', '为代码和文档工作流提供仓库访问。'],
  ['GitHub PAT', 'GitHub PAT'],
  ['Leave blank to keep current token', '留空以保留当前令牌'],
  ['Filesystem allowed roots', '文件系统允许的根目录'],
  ['One path per line, for example: workspace', '每行一个路径，例如：workspace'],
  ['Skills', 'Skills'],
  ['Loaded from backend skill registry', '从后端 Skill 注册表加载'],
  ['No skills found.', '未找到 Skills。'],
  ['Custom Models', '自定义模型'],
  ['Changes are saved to the backend settings store. Blank secret fields keep existing values.', '更改将保存到后端设置；敏感字段留空会保留现有值。'],
  ['Add Model', '添加模型'],
  ['Edit', '编辑'],
  ['Test', '测试'],
  ['Edit Custom Model', '编辑自定义模型'],
  ['Add Custom Model', '添加自定义模型'],
  ['Model label', '模型名称'],
  ['API URL', 'API 地址'],
  ['Model ID', '模型 ID'],
  ['Settings saved.', '设置已保存。'],
  ['Failed to load settings.', '加载设置失败。'],
  ['Failed to load skills.', '加载 Skills 失败。'],
  ['Failed to save settings.', '保存设置失败。'],

  ['Projects', '项目'],
  ['READ_ONLY', '只读'],
  ['Delete Project', '删除项目'],
  ['New Project', '新建项目'],
  ['No Projects yet. Bind an existing read-only Project folder.', '暂无项目，请绑定一个现有的只读项目文件夹。'],
  ['Conversations', '会话'],
  ['Files', '文件'],
  ['Project conversation history', '项目会话历史'],
  ['Conversation actions', '会话操作'],
  ['Expand all folders', '展开全部文件夹'],
  ['Collapse all folders', '收起全部文件夹'],
  ['No readable files', '没有可读文件'],
  ['Search Project', '搜索项目'],
  ['Chat', '对话'],
  ['Plan', '计划'],
  ['Changes', '改动'],
  ['New conversation', '新会话'],
  ['Select a readable file to preview it here.', '选择一个可读文件在此预览。'],
  ['Files actually read by the Agent. CURRENT means their hashes still match.', 'Agent 实际读取的文件；CURRENT 表示哈希仍然匹配。'],
  ['Evidence appears after the Agent reads Project files or a Plan is selected.', 'Agent 读取项目文件或选择计划后会显示证据。'],
  ['Read-only suggestions. Original Project files are never changed.', '仅提供只读建议，绝不会修改原始项目文件。'],
  ['Revalidate', '重新验证'],
  ["Compare each proposal's base hash with the current Project file", '将每项建议的基础哈希与当前项目文件进行比较'],
  ['Ask the Agent to modify a file to create a read-only proposal.', '让 Agent 提出文件修改，以生成只读建议。'],
  ['Readonly diff', '只读差异'],
  ['Ask the Project Agent to inspect the selected Project.', '让项目 Agent 检查所选项目。'],
  ['Project conversation navigation', '项目会话导航'],
  ['Ask about this read-only Project...', '询问这个只读项目...'],
  ['Send', '发送'],
  ['No Project Plans in this session.', '当前会话中暂无项目计划。'],
  ['Project plan conversation', '项目计划会话'],
  ['You - Plan request', '你 - 计划请求'],
  ['Project plan navigation', '项目计划导航'],
  ['Create a read-only inspection plan...', '创建只读检查计划...'],
  ['Create & run', '创建并运行'],
  ['Create Project', '创建项目'],
  ['Import an isolated copy of a folder into secure object storage.', '将文件夹的隔离副本导入安全对象存储。'],
  ['Project name', '项目名称'],
  ['Name this project', '为项目命名'],
  ['Project folder', '项目文件夹'],
  ['Source code, notes, and research files are supported.', '支持源代码、笔记和研究文件。'],
  ['Your original folder stays untouched.', '原始文件夹不会被修改。'],
  ['Yanban works only with the imported copy.', '研伴只处理导入的副本。'],
  ['Advanced filters', '高级筛选'],
  ['Optional include and ignore rules', '可选的包含与忽略规则'],
  ['Include rules', '包含规则'],
  ['Ignore rules', '忽略规则'],
  ['Import Project', '导入项目'],
  ['Rename conversation', '重命名会话'],
  ['Conversation name', '会话名称'],

  ['Expand', '展开'],
  ['Preview document', '预览文档'],
  ['Save to Knowledge Base', '存入知识库'],
  ['Select model', '选择模型'],
  ['Search Papers', '搜索论文'],
  ['Polish Paper', '润色论文'],
  ['Tool Trace', '工具轨迹'],
  ['Current conversation navigation', '当前会话导航'],
  ['Download started.', '下载已开始。'],
  ['Last updated:', '最后更新：'],
  ['Never', '从未'],
  ['API key configured', 'API 密钥已配置'],
  ['API key missing', '缺少 API 密钥'],
  ['Refresh models', '刷新模型'],
  ['Sync catalog', '同步模型目录'],
  ['PAT configured', 'PAT 已配置'],
  ['PAT missing', '缺少 PAT'],
  ['Enabled', '已启用'],
  ['Key set', '密钥已配置'],
  ['Key missing', '缺少密钥'],
  ['Demo account settings are read-only', 'Demo 账号为只读配置'],
  ['Guest users can use preset models and sample documents, but cannot modify API keys, models, MCP, Skills, or custom models.', '游客体验可以使用预置模型和样本文档，但不能修改 API Key、模型、MCP、Skills 或自定义模型。'],
  ['No custom models yet. Use Add Model above.', '尚未添加自定义模型。点击右上角添加。'],
  ['Model name (display)', '模型名称（显示用）'],
  ['For example: My DeepSeek V4 Pro', '例如：我的 DeepSeek V4 Pro'],
  ['For example: deepseek-v4-flash', '例如：deepseek-v4-flash'],
  ['API Key (leave blank to keep current)', 'API Key（留空保持不变）'],
  ['Please enter the model label, API URL, and model ID.', '请填写模型名称、API 地址和模型 ID'],
  ['Model updated.', '模型已更新'],
  ['Model added.', '模型已添加'],
  ['Failed to save model.', '保存模型失败'],
  ['Model deleted.', '模型已删除'],
  ['Failed to delete model.', '删除模型失败'],
  ['Connection succeeded', '连接成功'],
  ['Connection failed', '连接失败'],
  ['Test failed.', '测试失败'],
  ['Demo accounts cannot modify settings.', 'Demo 账号不能修改配置'],

  ['Drag and drop files here', '将文件拖放到这里'],
  ['PDF, DOCX, TXT, MD up to the backend upload limit.', '支持 PDF、DOCX、TXT、MD，大小不超过后端上传限制。'],
  ['Upload Files', '上传文件'],
  ['Stable', '稳定'],
  ['Delete this document?', '确定删除此文档吗？'],
  ['Showing the first', '当前显示前'],
  ['characters. The full text still participates in retrieval.', '个字符，完整文本仍会参与检索。'],
  ['The document may still be processing, or no text could be extracted.', '文档可能仍在处理，或未能提取到文本。'],
  ['PDF document', 'PDF 文档'],
  ['Word document', 'Word 文档'],
  ['Markdown file', 'Markdown 文件'],
  ['Text file', '文本文件'],
  ['Document file', '文档文件'],
  ['No run', '尚未运行'],
  ['Needs review', '需要检查'],
  ['Good', '良好'],
  ['High', '高'],
  ['Medium', '中'],
  ['Low', '低'],
  ['results', '条结果'],
  ['Top', '前'],
  ['of requested', '条，请求数量'],
  ['The current result set has no low-confidence chunks.', '当前结果中没有低置信度分块。'],
  ['results are below 0.50. Consider increasing Top K or rewriting the query.', '条结果低于 0.50，建议提高 Top K 或改写查询。'],

  ['System process', '系统过程'],
  ['Tool output', '工具输出'],
  ['You', '你'],
  ['Thinking...', '正在思考...'],
  ['Open paper workspace', '打开论文修改页'],
  ['Select model', '选择模型'],
  ['Uploading document...', '正在上传文档...'],
  ['Recently updated', '最近更新'],
  ['Session', '会话'],
  ['Built-in', '内置'],
  ['Document generated. You can preview, download, or save it to the Knowledge Base.', '文档已生成，可以预览、下载或存入知识库。'],
  ['The document generation tool has finished.', '文档生成工具已完成。'],
  ['Choosing the right tool.', '正在选择合适的工具。'],
  ['Generating a downloadable document.', '正在生成可下载文档。'],
  ['Searching the Knowledge Base.', '正在检索知识库。'],
  ['Searching the web.', '正在联网搜索资料。'],
  ['Searching and organizing relevant literature.', '正在检索并整理相关文献。'],
  ['Creating a literature-search task.', '正在创建文献检索任务。'],
  ['Checking literature-search progress.', '正在查看文献检索进度。'],
  ['Reading literature-search results.', '正在读取文献检索结果。'],
  ['Cancelling the background task.', '正在取消后台任务。'],
  ['Checking paper-polish progress.', '正在查看论文润色进度。'],
  ['Reading paper-polish results.', '正在读取论文润色结果。'],
  ['Calling a supporting tool.', '正在调用辅助工具。'],
  ['Tool execution did not complete; processing continued.', '工具调用未完成，已尝试继续处理。'],
  ['Background task completed.', '后台任务已完成。'],
  ['Background task failed.', '后台任务执行失败。'],
  ['Background task cancelled.', '后台任务已取消。'],
  ['Background task is still processing.', '后台任务仍在处理中。'],
  ['Processing', '处理中'],
  ['Processed', '已处理'],
  ['Unknown', '未知'],
  ['Just now', '刚刚'],

  ['Structure review pending', '结构确认待处理'],
  ['The current task needs a structure review. Submit a choice under Clarifications in the Review Workspace to continue processing.', '当前任务需要你确认论文结构。请在下方 Review Workspace 的 Clarifications 中提交选择，任务会继续后台执行。'],
  ['Paper structure review required', '需要确认论文结构'],
  ['Review now', '去确认'],
  ['Upload paper', '上传论文'],
  ['Choose tex / bib files and options', '选择 tex / bib 与参数'],
  ['In progress', '处理中'],
  ['View SSE logs', '查看 SSE 日志'],
  ['Download results', '下载结果'],
  ['Save final files', '保存最终文件'],
  ['Upload and Options', '上传与参数'],
  ['LaTeX main file (.tex, required)', 'LaTeX 主文件（.tex，必填）'],
  ['Choose main.tex', '点击选择 main.tex'],
  ['This entry tex file will be used as the LaTeX parsing entry point.', '主入口 tex 文件，后续会作为 LaTeX 解析入口'],
  ['Bibliography file (.bib, optional)', '参考文献文件（.bib，可选）'],
  ['Choose refs.bib (optional)', '点击选择 refs.bib（可选）'],
  ['Inline thebibliography samples are supported without a .bib file.', '无 .bib 时也支持内联 thebibliography 样例'],
  ['Target language', '目标语言'],
  ['Score threshold', '评分阈值'],
  ['Maximum rounds', '最大轮次'],
  ['Attempts per section', '单节尝试'],
  ['Minimum recommended literature', '推荐文献最少数量'],
  ['Maximum recommended literature', '推荐文献最多数量'],
  ['Start processing', '开始处理'],
  ['Task History', '历史任务'],
  ['No task history', '暂无历史任务'],
  ['Created:', '创建：'],
  ['Updated:', '更新：'],
  ['No result files', '暂无结果文件'],
  ['View', '查看'],
  ['Live Progress', '实时进度'],
  ['Task ID', '任务 ID'],
  ['Current status', '当前状态'],
  ['Error message', '错误信息'],
  ['Waiting for task events', '等待任务事件'],
  ['Raw SSE event log (debug)', '原始 SSE 事件日志（调试）'],
  ['Results Center', '结果中心'],
  ['Manage downloads, confirmations, previews, and reviews in one place', '下载、确认、预览与审查集中管理'],
  ['Available to download', '可下载'],
  ['Waiting for artifacts', '等待产物'],
  ['Source file:', '原始文件：'],
  ['Overview', '总览'],
  ['Current Notes', '当前说明'],
  ['Structure confirmation', '结构确认'],
  ['Section roles', '章节角色'],
  ['Preview suggestions', '预览建议'],
  ['Accepted', '已采纳'],
  ['Disclaimer', '免责声明'],
  ['Structure', '结构'],
  ['Your confirmation is required', '需要你的确认'],
  ['Blocking questions must be answered first. Defaults preserve the original structure whenever possible.', '阻塞型问题必须先回答。默认选项会优先保持原结构，避免任务跑偏。'],
  ['Required answer', '必须回答'],
  ['Can skip', '可跳过'],
  ['Type:', '类型：'],
  ['Related section index:', '相关章节序号：'],
  ['Answered:', '已回答：'],
  ['Keep all unchanged', '全部保持原样'],
  ['No structure confirmation items', '暂无结构确认项'],
  ['Section Roles', '章节角色'],
  ['Roles can be corrected manually', '可手动修正识别结果'],
  ['No section-role results', '暂无章节角色结果'],
  ['Online preview and selective acceptance', '在线预览与逐条采纳'],
  ['Basic: recommendations only', '基础版：只看建议'],
  ['Advanced: Diff + cite patch', '进阶版：Diff + cite patch'],
  ['Basic preview', '基础版预览'],
  ['Advanced preview', '进阶版预览'],
  ['No diff or suggestion results', '暂无 diff 或建议结果'],
  ['Accept', '采纳'],
  ['Reject', '拒绝'],
  ['Review report and suggested.bib', '审查报告与 suggested.bib'],
  ['Recommended references added', '新增推荐文献'],
  ['Existing references reused', '复用已有文献'],
  ['Citation locations updated', '更新引用位置'],
  ['Existing locations confirmed', '确认已有位置'],
  ['No review report or recommended literature', '暂无审查报告或推荐文献'],
  ['Claims needing support in the introduction', '引言待支持论断'],
  ['Shows which manuscript claims require literature support', '用于说明文献应支持的正文内容'],
  ['No verified evidence; do not write this directly into the paper.', '无真实 evidence，禁止直接写入论文。'],
  ['Recommended Literature', '推荐文献列表'],
  ['Verify every item before submission', '请投稿前逐条核验'],
  ['+ Add Model', '+ 添加模型'],
  ['Close', '关闭'],
  ['close', '关闭'],
  ['Choose a project folder', '选择项目文件夹'],
  ['Browse', '浏览'],
  ['Change', '更换'],
  ['Report', '报告'],
  ['Current conversation navigation', '当前会话导航条'],
  ['No section progress', '暂无章节进度'],
  ['No attempt data', '暂无尝试信息'],
  ['Not connected', '未连接'],
  ['Running now.', '正在运行。'],
  ['Queued.', '已排队。'],
  ['No detailed result yet.', '暂无详细结果。'],
  ['Project Agent is working...', '项目 Agent 正在工作...'],
  ['Process details', '过程详情'],
  ['Inspecting the authorized Project directory manifest.', '正在检查已授权的项目目录清单。'],
  ['Searching authorized Project-relative files.', '正在搜索已授权的项目相对路径文件。'],
  ['Reading an authorized Project-relative file.', '正在读取已授权的项目相对路径文件。'],
  ['Calling an authorized read-only Project tool.', '正在调用已授权的只读项目工具。'],
  ['Project tool completed.', '项目工具已完成。'],
  ['Project tool failed; the Agent may retry with another authorized read operation.', '项目工具失败，Agent 可能会尝试其他已授权的只读操作。'],
  ['Starting authenticated read-only Project request.', '正在启动已认证的只读项目请求。'],
  ['Project streaming connection failed.', '项目流式连接失败。'],
  ['Project streaming is unavailable.', '项目流式连接不可用。'],
  ['The upload connection was interrupted. Check the folder size and try again.', '上传连接中断，请检查文件夹大小后重试。'],
  ['Request failed.', '请求失败。'],
  ['Plan is still running beyond the expected five-minute window; use Refresh to check its latest status.', '计划运行已超过预期的五分钟，请使用“刷新”检查最新状态。'],
  ['Conversation name is required.', '请输入会话名称。'],
  ['Current Project Agent request is still running. Please wait before deleting a conversation.', '当前项目 Agent 请求仍在运行，请稍后再删除会话。'],
  ['Project name and a selected Project folder are required.', '请填写项目名称并选择项目文件夹。'],
  ['At least one include rule is required.', '至少需要一条包含规则。'],
  ['No files remain after applying the Project filters.', '应用项目筛选规则后没有剩余文件。'],
  ['Hide section', '隐藏区域'],
  ['Show section', '显示区域'],
  ['loading', '加载中'],
];

function createTranslationMap(pairs: readonly TranslationPair[], reverse = false) {
  const map = new Map<string, string>();
  for (const [english, chinese] of pairs) {
    const key = reverse ? chinese : english;
    if (!map.has(key)) map.set(key, reverse ? english : chinese);
  }
  return map;
}

const enToZh = createTranslationMap(UI_TRANSLATIONS);
const zhToEn = createTranslationMap(UI_TRANSLATIONS, true);
const englishNormalizations = new Map([['Please Input', 'Enter a value']]);
const statusLabels = {
  READY: ['Ready', '已就绪'],
  PROCESSING: ['Processing', '处理中'],
  UPLOADING: ['Uploading', '上传中'],
  FAILED: ['Failed', '失败'],
  PENDING: ['Pending', '等待中'],
  RUNNING: ['Running', '运行中'],
  PAUSED: ['Paused', '已暂停'],
  COMPLETED: ['Completed', '已完成'],
  CANCELLED: ['Cancelled', '已取消'],
  WAITING_INPUT: ['Awaiting review', '等待确认'],
  CURRENT: ['Current', '当前'],
  STALE: ['Stale', '已过期'],
  TRUSTED: ['Trusted', '可信'],
  UNTRUSTED: ['Untrusted', '不可信'],
} as const;
const translatedAttributes = ['placeholder', 'title', 'aria-label', 'alt'] as const;
const skipSelector = [
  '[data-i18n-skip]',
  '.language-toggle',
  '.markdown-body',
  '.message-row--user .message-text-block',
  '.message-row--assistant .message-text-block',
  '.chat-artifact-preview',
  '.kb-preview-content',
  '.kb-preview__content',
  '.search-result-row p',
  '.selected-result-panel p',
  '.paper-preview-content',
  '.paper-diff-view',
  '.project-file-preview',
  'pre',
  'code',
].join(',');

function translateValue(value: string, locale: AppLocale) {
  const trimmed = value.trim();
  if (!trimmed) return value;
  const map = locale === 'zh-CN' ? enToZh : zhToEn;
  let translated = map.get(trimmed);
  if (!translated && locale === 'en-US') translated = englishNormalizations.get(trimmed);

  const status = statusLabels[trimmed as keyof typeof statusLabels];
  if (!translated && status) translated = locale === 'zh-CN' ? status[1] : status[0];

  if (!translated) {
    const totalMatch = trimmed.match(/^(\d+) total$/);
    const sessionMatch = trimmed.match(/^(\d+) sessions?$/);
    const resultMatch = trimmed.match(/^(\d+) results?$/);
    const topResultsMatch = trimmed.match(/^Top (\d+) of requested (\d+)$/);
    const countTabMatch = trimmed.match(/^(Sections|Preview|Report) \((\d+)\)$/);
    const documentMatch = trimmed.match(/^Document #(\d+) · (.+)$/);
    const lastUpdatedMatch = trimmed.match(/^Last updated:\s*(.+)$/);
    const projectProcessMatch = trimmed.match(/^Process completed - (.+)$/);
    const stillWorkingMatch = trimmed.match(/^Still working\. Current status: (.+)\.$/);
    const finishedStatusMatch = trimmed.match(/^Finished with status: (.+)\.$/);
    const observedPathMatch = trimmed.match(/^Observed Project-relative path: (.+)$/);
    if (locale === 'zh-CN' && totalMatch) translated = `共 ${totalMatch[1]} 项`;
    if (locale === 'zh-CN' && sessionMatch) translated = `${sessionMatch[1]} 个会话`;
    if (locale === 'zh-CN' && resultMatch) translated = `${resultMatch[1]} 条结果`;
    if (locale === 'zh-CN' && topResultsMatch) translated = `显示前 ${topResultsMatch[1]} 条，请求 ${topResultsMatch[2]} 条`;
    if (locale === 'zh-CN' && countTabMatch) translated = `${enToZh.get(countTabMatch[1]) || countTabMatch[1]}（${countTabMatch[2]}）`;
    if (locale === 'zh-CN' && documentMatch) translated = `文档 #${documentMatch[1]} · ${enToZh.get(documentMatch[2]) || documentMatch[2]}`;
    if (locale === 'zh-CN' && lastUpdatedMatch) translated = `最后更新：${lastUpdatedMatch[1]}`;
    if (locale === 'zh-CN' && projectProcessMatch) translated = `过程已完成 - ${projectProcessMatch[1]}`;
    if (locale === 'zh-CN' && stillWorkingMatch) translated = `仍在处理中，当前状态：${stillWorkingMatch[1]}。`;
    if (locale === 'zh-CN' && finishedStatusMatch) translated = `已结束，状态：${finishedStatusMatch[1]}。`;
    if (locale === 'zh-CN' && observedPathMatch) translated = `已读取项目相对路径：${observedPathMatch[1]}`;
    if (locale === 'en-US') {
      const chineseTotal = trimmed.match(/^共\s*(\d+)\s*项$/);
      const chineseSessions = trimmed.match(/^(\d+)\s*个会话$/);
      const processed = trimmed.match(/^已处理(?:\s+(.+))?$/);
      const minutesAgo = trimmed.match(/^(\d+)\s*分钟前$/);
      const hoursAgo = trimmed.match(/^(\d+)\s*小时前$/);
      const daysAgo = trimmed.match(/^(\d+)\s*天前$/);
      const sseStatus = trimmed.match(/^SSE\s+(未连接|连接中|已连接|已关闭|异常断开)$/);
      const paperTab = trimmed.match(/^(结构|章节|预览|报告)[(（](\d+)[)）]$/);
      const pendingItems = trimmed.match(/^(\d+)\s*个确认项等待处理，提交后任务会继续执行。$/);
      const createdAt = trimmed.match(/^创建：(.+)$/);
      const updatedAt = trimmed.match(/^更新：(.+)$/);
      const sectionLine = trimmed.match(/^章节：(.+)$/);
      const attemptLine = trimmed.match(/^尝试：(.+)$/);
      const pendingCount = trimmed.match(/^(\d+)\s*个待确认$/);
      const sectionCount = trimmed.match(/^(\d+)\s*节$/);
      const itemCount = trimmed.match(/^(\d+)\s*条(?:建议)?$/);
      const versionCount = trimmed.match(/^(\d+)\s*个版本$/);
      const diagnosticCount = trimmed.match(/^(\d+)\s*个诊断文件$/);
      const acceptedCount = trimmed.match(/^已采纳\s*(\d+)$/);
      const grade = trimmed.match(/^(.+)\s*类$/);
      const claim = trimmed.match(/^论断\s*(\d+)$/);
      const recentUpdate = trimmed.match(/^最近更新\s+(.+)$/);
      if (chineseTotal) translated = `${chineseTotal[1]} total`;
      if (chineseSessions) translated = `${chineseSessions[1]} ${chineseSessions[1] === '1' ? 'session' : 'sessions'}`;
      if (processed) translated = processed[1] ? `Processed ${processed[1]}` : 'Processed';
      if (minutesAgo) translated = `${minutesAgo[1]} minutes ago`;
      if (hoursAgo) translated = `${hoursAgo[1]} hours ago`;
      if (daysAgo) translated = `${daysAgo[1]} days ago`;
      if (sseStatus) translated = `SSE ${zhToEn.get(sseStatus[1]) || sseStatus[1]}`;
      if (paperTab) translated = `${zhToEn.get(paperTab[1]) || paperTab[1]} (${paperTab[2]})`;
      if (pendingItems) translated = `${pendingItems[1]} confirmation item(s) are waiting; the task will continue after submission.`;
      if (createdAt) translated = `Created: ${createdAt[1]}`;
      if (updatedAt) translated = `Updated: ${updatedAt[1]}`;
      if (sectionLine) translated = `Sections: ${sectionLine[1]}`;
      if (attemptLine) translated = `Attempts: ${attemptLine[1]}`;
      if (pendingCount) translated = `${pendingCount[1]} pending`;
      if (sectionCount) translated = `${sectionCount[1]} sections`;
      if (itemCount) translated = `${itemCount[1]} items`;
      if (versionCount) translated = `${versionCount[1]} versions`;
      if (diagnosticCount) translated = `${diagnosticCount[1]} diagnostic files`;
      if (acceptedCount) translated = `${acceptedCount[1]} accepted`;
      if (grade) translated = `Grade ${grade[1]}`;
      if (claim) translated = `Claim ${claim[1]}`;
      if (recentUpdate) translated = `Recently updated ${recentUpdate[1]}`;
    }
  }

  if (!translated && locale === 'en-US' && trimmed === '1 sessions') translated = '1 session';

  if (!translated) return value;
  const leading = value.match(/^\s*/)?.[0] || '';
  const trailing = value.match(/\s*$/)?.[0] || '';
  return `${leading}${translated}${trailing}`;
}

function shouldSkip(element: Element | null) {
  return Boolean(element?.closest(skipSelector));
}

function translateTree(root: ParentNode, locale: AppLocale) {
  const ownerDocument = root instanceof Document ? root : root.ownerDocument;
  if (!ownerDocument) return;
  const walker = ownerDocument.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const textNodes: Text[] = [];
  while (walker.nextNode()) textNodes.push(walker.currentNode as Text);
  for (const node of textNodes) {
    const parent = node.parentElement;
    if (!parent || shouldSkip(parent) || ['SCRIPT', 'STYLE'].includes(parent.tagName)) continue;
    const nextValue = translateValue(node.nodeValue || '', locale);
    if (nextValue !== node.nodeValue) node.nodeValue = nextValue;
  }

  const elements = root instanceof Element ? [root, ...root.querySelectorAll('*')] : [...root.querySelectorAll('*')];
  for (const element of elements) {
    if (shouldSkip(element)) continue;
    for (const attribute of translatedAttributes) {
      const current = element.getAttribute(attribute);
      if (!current) continue;
      const nextValue = translateValue(current, locale);
      if (nextValue !== current) element.setAttribute(attribute, nextValue);
    }
  }
}

export function useUiTranslationBridge(localeOverride?: Ref<AppLocale>) {
  const { locale: sharedLocale } = useI18n();
  const activeLocale = localeOverride || sharedLocale;
  let observer: MutationObserver | null = null;
  let queued = false;
  const pendingRoots = new Set<ParentNode>();

  function queueTranslation(root?: ParentNode) {
    if (typeof document === 'undefined') return;
    pendingRoots.add(root || document.body);
    if (queued) return;
    queued = true;
    queueMicrotask(() => {
      queued = false;
      const roots = [...pendingRoots];
      pendingRoots.clear();
      for (const pendingRoot of roots) {
        if (pendingRoot instanceof Node && !pendingRoot.isConnected) continue;
        translateTree(pendingRoot, activeLocale.value);
      }
    });
  }

  watch(activeLocale, () => void nextTick(() => queueTranslation(document.body)));

  onMounted(() => {
    queueTranslation(document.body);
    observer = new MutationObserver((records) => {
      for (const record of records) {
        if (record.type === 'childList') {
          record.addedNodes.forEach((node) => queueTranslation(node instanceof Element ? node : node.parentNode || document.body));
        } else {
          queueTranslation(record.target instanceof Element ? record.target : record.target.parentNode || document.body);
        }
      }
    });
    observer.observe(document.body, {
      subtree: true,
      childList: true,
      characterData: true,
      attributes: true,
      attributeFilter: [...translatedAttributes],
    });
  });

  onBeforeUnmount(() => observer?.disconnect());
}
