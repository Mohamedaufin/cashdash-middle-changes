import os

file_path = r's:\AndroidStudioProjects\AndroidStudioProjects\cashdash\app\src\main\java\com\cash\dash\CategoryAnalysisActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# keep lines up to line 200 (index 0 to 200)
new_lines = lines[:201]

new_lines.extend([
'            // Calculate End Date of the week\n',
'            cal.add(Calendar.DAY_OF_YEAR, 6)\n',
'            val endDate = sdf.format(cal.time)\n',
'            \n',
'            labels.add(f"{startDate}-{endDate}")\n', 
'        }\n',
'        return labels\n',
'    }\n',
'\n',
'    override fun onSaveInstanceState(outState: Bundle) {\n',
'        super.onSaveInstanceState(outState)\n',
'        outState.putString("categoryName", categoryName)\n',
'    }\n',
'\n',
'    override fun onRestoreInstanceState(savedInstanceState: Bundle) {\n',
'        super.onRestoreInstanceState(savedInstanceState)\n',
'        categoryName = savedInstanceState.getString("categoryName", "Unknown")\n',
'        tvCategoryName.text = categoryName\n',
'        refreshUI()\n',
'    }\n',
'}\n'
])

with open(file_path, 'w', encoding='utf-8') as f:
    f.writelines(new_lines)
print('Fixed successfully')
