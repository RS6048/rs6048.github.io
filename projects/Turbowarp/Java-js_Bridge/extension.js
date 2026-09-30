(function (Scratch) {
  'use strict';

  // 本扩展需要非沙箱模式才能访问 window.cefQuery（JCEF 注入的 JS-Java 桥）
  if (!Scratch.extensions.unsandboxed) {
    throw new Error('Run it unsandbox!!!');
  }

  function callJava(payload) {
    return new Promise(function (resolve, reject) {
      window.cefQuery({
        request: JSON.stringify(payload),
        persistent: false,
        onSuccess: function (response) {
          try {
            resolve(JSON.parse(response));
          } catch (e) {
            reject(new Error('Unable to process the respond: ' + response));
          }
        },
        onFailure: function (errorCode, errorMessage) {
          reject(new Error(errorMessage || ('JCEF error ' + errorCode)));
        }
      });
    });
  }

  async function exec(action, path, data) {
    const payload = { action: action, path: String(path || '') };
    if (data !== undefined) payload.data = String(data);
    const res = await callJava(payload);
    if (!res.ok) throw new Error(res.error || ('File execute failed:' + action));
    return res.data;
  }

  class LocalFile {
    getInfo() {
      return {
        id: 'localfile',
        name: 'Local File',
        color1: '#0f9d58',
        color2: '#0b7a44',
        color3: '#0f9d58',
        docsURI: '',
        blocks: [
          {
            opcode: 'readFile',
            blockType: Scratch.BlockType.REPORTER,
            text: 'read [PATH]',
            arguments: {
              PATH: { type: Scratch.ArgumentType.STRING, defaultValue: 'data.txt' }
            }
          },
          {
            opcode: 'writeFile',
            blockType: Scratch.BlockType.COMMAND,
            text: 'write [DATA] into [PATH]',
            arguments: {
              DATA: { type: Scratch.ArgumentType.STRING, defaultValue: 'hello' },
              PATH: { type: Scratch.ArgumentType.STRING, defaultValue: 'data.txt' }
            }
          },
          {
            opcode: 'appendFile',
            blockType: Scratch.BlockType.COMMAND,
            text: 'append [DATA] to [PATH]',
            arguments: {
              DATA: { type: Scratch.ArgumentType.STRING, defaultValue: 'hello' },
              PATH: { type: Scratch.ArgumentType.STRING, defaultValue: 'data.txt' }
            }
          },
          {
            opcode: 'listDir',
            blockType: Scratch.BlockType.REPORTER,
            text: 'list directory [PATH]',
            arguments: {
              PATH: { type: Scratch.ArgumentType.STRING, defaultValue: '.' }
            }
          },
          {
            opcode: 'exists',
            blockType: Scratch.BlockType.BOOLEAN,
            text: 'exists [PATH]',
            arguments: {
              PATH: { type: Scratch.ArgumentType.STRING, defaultValue: 'data.txt' }
            }
          },
          {
            opcode: 'deleteFile',
            blockType: Scratch.BlockType.COMMAND,
            text: 'delete [PATH]',
            arguments: {
              PATH: { type: Scratch.ArgumentType.STRING, defaultValue: 'data.txt' }
            }
          },
          {
            opcode: 'makeDir',
            blockType: Scratch.BlockType.COMMAND,
            text: 'make directory [PATH]',
            arguments: {
              PATH: { type: Scratch.ArgumentType.STRING, defaultValue: 'newdir' }
            }
          },
          {
            opcode: 'fileSize',
            blockType: Scratch.BlockType.REPORTER,
            text: 'file size of [PATH]',
            arguments: {
              PATH: { type: Scratch.ArgumentType.STRING, defaultValue: 'data.txt' }
            }
          }
        ]
      };
    }

    async readFile(args) {
      return await exec('readFile', args.PATH);
    }

    async writeFile(args) {
      await exec('writeFile', args.PATH, args.DATA);
    }

    async appendFile(args) {
      await exec('appendFile', args.PATH, args.DATA);
    }

    async listDir(args) {
      return await exec('listDir', args.PATH);
    }

    async exists(args) {
      return (await exec('exists', args.PATH)) === 'true';
    }

    async deleteFile(args) {
      await exec('deleteFile', args.PATH);
    }

    async makeDir(args) {
      await exec('makeDir', args.PATH);
    }

    async fileSize(args) {
      const size = await exec('fileSize', args.PATH);
      return Number(size) || 0;
    }
  }

  Scratch.extensions.register(new LocalFile());
})(Scratch);