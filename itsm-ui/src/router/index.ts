import {createRouter, createWebHistory} from 'vue-router';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      meta: {title: '登录'},
      component: () => import('../views/LoginView.vue')
    },
    {
      path: '/',
      component: () => import('../layouts/AdminLayout.vue'),
      redirect: '/workbench',
      children: [
        {
          path: 'workbench',
          name: 'workbench',
          meta: {title: '工作台'},
          component: () => import('../views/WorkbenchView.vue')
        },
        {path: 'incidents', name: 'incident-list', meta: {title: '事件管理'}, component: () => import('../views/incident/IncidentListView.vue')},
        {path: 'incidents/create', name: 'incident-create', meta: {title: '新增事件'}, component: () => import('../views/incident/IncidentDetailView.vue')},
        {path: 'incidents/:id', name: 'incident-detail', meta: {title: '事件详情'}, component: () => import('../views/incident/IncidentDetailView.vue')},
        // ===== M2 工单域(原型) =====
        {
          path: 'tickets',
          name: 'ticket-list',
          meta: {title: '工单管理'},
          component: () => import('../views/ticket/TicketListView.vue')
        },
        {
          path: 'tickets/create',
          name: 'ticket-create',
          meta: {title: '新建工单'},
          component: () => import('../views/ticket/TicketCreateView.vue')
        },
        {
          path: 'tickets/:id',
          name: 'ticket-detail',
          meta: {title: '工单详情'},
          component: () => import('../views/ticket/TicketDetailView.vue')
        },
        {
          path: 'my-tickets',
          redirect: '/my-tickets/todo'
        },
        // ===== 我的工单(四类视图,共用页面组件) =====
        {
          path: 'my-tickets/drafts',
          name: 'my-drafts',
          meta: {title: '我的草稿'},
          component: () => import('../views/ticket/MyTicketsView.vue')
        },
        {
          path: 'my-tickets/todo',
          name: 'my-todo',
          meta: {title: '我的待办'},
          component: () => import('../views/ticket/MyTicketsView.vue')
        },
        {
          path: 'my-tickets/submitted',
          name: 'my-submitted',
          meta: {title: '我提交的工单'},
          component: () => import('../views/ticket/MyTicketsView.vue')
        },
        {
          path: 'my-tickets/participated',
          name: 'my-participated',
          meta: {title: '我参与的工单'},
          component: () => import('../views/ticket/MyTicketsView.vue')
        },
        // ===== 系统管理(原型) =====
        {
          path: 'system/user',
          name: 'system-user',
          meta: {title: '用户管理'},
          component: () => import('../views/system/UserView.vue')
        },
        {
          path: 'system/dept',
          name: 'system-dept',
          meta: {title: '部门管理'},
          component: () => import('../views/system/DeptView.vue')
        },
        {
          path: 'system/role',
          name: 'system-role',
          meta: {title: '角色管理'},
          component: () => import('../views/system/RoleView.vue')
        },
        {
          path: 'system/menu',
          name: 'system-menu',
          meta: {title: '菜单管理'},
          component: () => import('../views/system/MenuView.vue')
        },
        {
          path: 'system/process',
          name: 'system-process',
          meta: {title: '流程管理'},
          component: () => import('../views/system/ProcessView.vue')
        }
      ]
    }
  ]
});

export default router;

