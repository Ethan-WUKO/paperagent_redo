import axios from 'axios';
import router from '@/router';

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 120000,
});

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('yanban_access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('yanban_access_token');
      localStorage.removeItem('yanban_refresh_token');
      if (router.currentRoute.value.path !== '/login') {
        await router.push('/login');
      }
    }
    return Promise.reject(error);
  },
);

export default http;
