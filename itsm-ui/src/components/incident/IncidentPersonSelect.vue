<script setup lang="ts">
import { ref, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { incidentApi, type Recipient } from "../../api/incident";
defineProps<{ modelValue?: string; placeholder?: string }>();
const emit = defineEmits<{ (e: "update:modelValue", value: string): void }>();
const options = ref<Recipient[]>([]),
  loading = ref(false),
  total = ref(0),
  page = ref(1),
  keyword = ref("");
let seq = 0;
async function search(value = "", append = false) {
  const current = ++seq;
  keyword.value = value;
  if (!append) page.value = 1;
  loading.value = true;
  try {
    const result = await incidentApi.recipients({
      keyword: value,
      page: page.value,
      size: 20,
    });
    if (current !== seq) return;
    options.value = append
      ? [...options.value, ...result.records]
      : result.records;
    total.value = result.total;
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    if (current === seq) loading.value = false;
  }
}
onMounted(() => search());
</script>
<template>
  <el-select
    :model-value="modelValue"
    clearable
    filterable
    remote
    :remote-method="search"
    :loading="loading"
    :placeholder="placeholder || '姓名、邮箱或手机号'"
    @update:model-value="emit('update:modelValue', $event || '')"
    ><el-option
      v-for="p in options"
      :key="p.id"
      :value="p.id"
      :label="`${p.nickname} (${p.username}) · ${p.deptName}`"
    /><template #footer
      ><el-button
        v-if="options.length < total"
        link
        @click="
          page++;
          search(keyword, true);
        "
        >加载更多</el-button
      ></template
    ></el-select
  >
</template>
