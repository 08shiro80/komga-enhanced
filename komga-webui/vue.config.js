// vue.config.js
module.exports = {
  // with './' the dev server cannot load any arbitrary path
  // with '/' the prod build generates some url(/fonts…) calls in the css chunks, which doesn't work with a servlet context path
  publicPath: process.env.NODE_ENV === 'production' ? './' : '/',

  pluginOptions: {
    i18n: {
      locale: 'en',
      fallbackLocale: 'en',
      localeDir: 'locales',
      enableInSFC: false,
    },
  },

  // Silence Vuetify SASS deprecation warnings (from node_modules)
  css: {
    loaderOptions: {
      sass: {
        sassOptions: {
          quietDeps: true,
          silenceDeprecations: ['slash-div', 'legacy-js-api'],
        },
      },
      scss: {
        sassOptions: {
          quietDeps: true,
          silenceDeprecations: ['slash-div', 'legacy-js-api'],
        },
      },
    },
  },

  devServer: {
    allowedHosts: 'all',
    client: {
      webSocketURL: 'ws://0.0.0.0:8081/ws',
    },
  },

  configureWebpack: (config) => {
    config.module.rules.push({
      test: [
        /readium\/.*\.css.resource$/,
        /r2d2bc\/.*\.css.resource$/,
      ],
      type: 'asset/resource',
      generator: {
        filename: 'css/[hash].css[query]',
      },
    })

    const MiniCssExtractPlugin = config.plugins.find(
      (p) => p.constructor.name === 'MiniCssExtractPlugin',
    )
    if (MiniCssExtractPlugin) {
      MiniCssExtractPlugin.options.ignoreOrder = true
    }
  },
}
