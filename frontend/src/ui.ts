import { createDiscreteApi, darkTheme } from 'naive-ui';

export const ui = createDiscreteApi(['message'], {
  configProviderProps: {
    theme: darkTheme,
  },
});
