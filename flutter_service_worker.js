'use strict';
const MANIFEST = 'flutter-app-manifest';
const TEMP = 'flutter-temp-cache';
const CACHE_NAME = 'flutter-app-cache';

const RESOURCES = {".git/COMMIT_EDITMSG": "529768c975e2e1ea3a98acdb0912dd48",
".git/HEAD": "4cf2d64e44205fe628ddd534e1151b58",
".git/config": "63347cebc48eb74bf1aadee231ee40e0",
".git/description": "a0a7c3fff21f2aea3cfa1d0316dd816c",
".git/hooks/applypatch-msg.sample": "ce562e08d8098926a3862fc6e7905199",
".git/hooks/commit-msg.sample": "579a3c1e12a1e74a98169175fb913012",
".git/hooks/fsmonitor-watchman.sample": "a0b2633a2c8e97501610bd3f73da66fc",
".git/hooks/post-update.sample": "2b7ea5cee3c49ff53d41e00785eb974c",
".git/hooks/pre-applypatch.sample": "054f9ffb8bfe04a599751cc757226dda",
".git/hooks/pre-commit.sample": "305eadbbcd6f6d2567e033ad12aabbc4",
".git/hooks/pre-merge-commit.sample": "39cb268e2a85d436b9eb6f47614c3cbc",
".git/hooks/pre-push.sample": "2c642152299a94e05ea26eae11993b13",
".git/hooks/pre-rebase.sample": "56e45f2bcbc8226d2b4200f7c46371bf",
".git/hooks/pre-receive.sample": "2ad18ec82c20af7b5926ed9cea6aeedd",
".git/hooks/prepare-commit-msg.sample": "2b5c047bdb474555e1787db32b2d2fc5",
".git/hooks/push-to-checkout.sample": "c7ab00c7784efeadad3ae9b228d4b4db",
".git/hooks/update.sample": "647ae13c682f7827c22f5fc08a03674e",
".git/index": "a2780b75a546145ff7c99e4edfa2eb6f",
".git/info/exclude": "036208b4a1ab4a235d75c181e685e5a3",
".git/logs/HEAD": "3e94940b1296288f371fa0360796ceca",
".git/logs/refs/heads/master": "3e94940b1296288f371fa0360796ceca",
".git/logs/refs/remotes/origin/gh-pages": "f9f600e3523be3f27f6f6d7d7bfd4a3e",
".git/objects/03/eaddffb9c0e55fb7b5f9b378d9134d8d75dd37": "87850ce0a3dd72f458581004b58ac0d6",
".git/objects/04/5f738e90b531cd900aceac8574c62b1d054319": "afcbd1b2897ff99b8d72821712000355",
".git/objects/06/c23c850c8442262f698d5823447c570f605a2e": "71000b1f87cb02fe132a4e708e1d09d4",
".git/objects/0b/437a59fbee9ebd36177bd66fa6f97d075a0e93": "8905fa8fb8cfa435d00d2153cedd2bd1",
".git/objects/0b/faffcfa689e64fc3d828049d5682d5aa8088ef": "a1cb617ef602e8c35c12b441f300fbef",
".git/objects/0d/1520c5dab9966a51cd787d9b83b314274ef888": "bc105c1907fbe0f546be10e19a48540b",
".git/objects/0f/a697f563f3692273a55d89a7b0107c5181f982": "2cdfa1192c2ad58c26b2d819c52dfc0d",
".git/objects/14/a45a25922e637a7b62ccaf656f094e79e4eaef": "c6a1cfcda8d4ec7d0c69ba7d5ce34885",
".git/objects/14/d759c46ea938ea4842b9e10a46b30fda13e8b2": "032e2f040d8d395568eeb8ae94092378",
".git/objects/16/5da67191b73406e15fc3e6cf7cda3c195dc735": "86cfac30d97fb45bba2f4417782645d6",
".git/objects/18/c7e1929bc1e4a06110b81bff3b22e6cd4e3512": "39abecf732b3d1121a3fc1f74ee58f9f",
".git/objects/1c/bc914427fdbc4396339aebb16061a1f262a66d": "9124149355908306d78ad2d703c64862",
".git/objects/1e/25fb4841dbfcbc6e4fa75d9417a4113ba250bc": "e91280155bc02e320c2a664e7fefc7b5",
".git/objects/1f/45b5bcaac804825befd9117111e700e8fcb782": "7a9d811fd6ce7c7455466153561fb479",
".git/objects/20/1afe538261bd7f9a38bed0524669398070d046": "82a4d6c731c1d8cdc48bce3ab3c11172",
".git/objects/23/cd1aac5ab7688e7e0e39ca67adc008e906590e": "643a505bddf0f1ae5cf90cd44084ce7d",
".git/objects/25/8b3eee70f98b2ece403869d9fe41ff8d32b7e1": "05e38b9242f2ece7b4208c191bc7b258",
".git/objects/26/0d4820a0e3ba9052b6169ce69e05b716eb7b30": "ffb99071ff9a88657b9ab9d07956bac5",
".git/objects/26/d0f79ec51a1ac52c893347162f4669693e97df": "2696bd9051f34886e0d462b23be3f4ff",
".git/objects/29/55cffe0b199603d3ec43d8b42db44196c1d42a": "990be6c0e04eddbbcddc7297e3833a94",
".git/objects/2e/78901627ff89b49833b1241b70705914460832": "cb0e66233d2c9151c4e5fbd0eef785c9",
".git/objects/2f/463ebd6074276552e08b685606ad7b0f30299f": "bf06e900b3d71253886ce39bbd882ba1",
".git/objects/32/aa3cae58a7432051fc105cc91fca4d95d1d011": "4f8558ca16d04c4f28116d3292ae263d",
".git/objects/39/25a24329469c7db925a24004b549d6b6e54ce1": "8a57e4c8ef54de0b9b27bddeb53405aa",
".git/objects/3a/7525f2996a1138fe67d2a0904bf5d214bfd22c": "ab6f2f6356cba61e57d5c10c2e18739d",
".git/objects/3e/baa372e1328b3c91037a75c8e7c2952fbe9204": "5de7616432b4937a3396061ac3d9036c",
".git/objects/3f/438904e757250327192d6e2c03777af2c058fa": "9d84c0bd8970f4b8880e4d8c390a902b",
".git/objects/46/4ab5882a2234c39b1a4dbad5feba0954478155": "2e52a767dc04391de7b4d0beb32e7fc4",
".git/objects/47/9730dba35a77c1c2324247a9ecf5d169075e6b": "f33b2fd8d5cc5e778e8ef98e488597c9",
".git/objects/4a/73ffee73f2d06cc88ed79f51069bbb7ea0a4e4": "35440cd3ce1f77c9b07b67b47b31d4bb",
".git/objects/51/e53ba84cb55164a874c20c629b30eac62de912": "ea6317937908801d8443dc8e07d153f5",
".git/objects/52/497de176e3975df88be19380a8b9e2b3a9bbc8": "a1321485c38810f6bcbeb7bb71df0552",
".git/objects/57/140a721e33f659996c270f433c41cb888b1446": "bb169b1188670fc82bafda76990e1626",
".git/objects/58/417734214e9aeb7e1cd6bdf8aaeb1f97adce9a": "e9d5e03a004816a5f447e63104cf01c3",
".git/objects/58/b9e0a624fd903d5e63f7d70bbbc629af231a8f": "3edaf9821758c8b5d31cf5e25ba1f20d",
".git/objects/5a/11a6a129e604393f512117a61942ae8cbdd671": "2c3560c170644ef7ab0778010e109ec2",
".git/objects/5d/15fadf1864d70c7184fca7d3efde79cdf68af5": "79a44d8578cc18e3add64aa6a97f0da0",
".git/objects/62/82116d7f47dc42650769d9335255a35878501d": "959e8b6644879f1e502ea881be56a79a",
".git/objects/69/dd618354fa4dade8a26e0fd18f5e87dd079236": "8cc17911af57a5f6dc0b9ee255bb1a93",
".git/objects/6b/e909fbf40b23748412f0ea89bf0fae827ed976": "5f118419157d9534688915220cc803f7",
".git/objects/6c/cb1c35c825e946ab3af393b02f26c4d2195c53": "85bbd5077b50e4b182c960b40eb26f06",
".git/objects/6e/1aa69ca6d8fdde2faca55abe274d36c1ef19e5": "3f3dba8a5e7643fa8cd7521eb67466b8",
".git/objects/76/3155233441556303b4a31d780d0961d5299f63": "5eca3017343f3dffb5c11cae56e3720c",
".git/objects/84/0516208d35dcb4298847ab835e2ef84ada92fa": "36a4a870d8d9c1c623d8e1be329049da",
".git/objects/85/6a39233232244ba2497a38bdd13b2f0db12c82": "eef4643a9711cce94f555ae60fecd388",
".git/objects/88/cfd48dff1169879ba46840804b412fe02fefd6": "e42aaae6a4cbfbc9f6326f1fa9e3380c",
".git/objects/8a/aa46ac1ae21512746f852a42ba87e4165dfdd1": "1d8820d345e38b30de033aa4b5a23e7b",
".git/objects/8b/29f79261e459d48114e6d58d5214075dedefcf": "541c1300471a32f3a3e00de7c5b20da6",
".git/objects/8e/5e4eb9adc7d9acab56a70984e3a02346f27d2b": "95f582781cf6618738a3ff0b5d309df3",
".git/objects/8f/e7af5a3e840b75b70e59c3ffda1b58e84a5a1c": "e3695ae5742d7e56a9c696f82745288d",
".git/objects/90/bcfcf0a77ab618a826db0fd8b0942963b653af": "fc109675cdf1233dd6599a4c3c0a7a69",
".git/objects/96/5ee8d976ac099c4e2f502eb4c64e7851c37f56": "eb774201f161eec8f8c67974357236e4",
".git/objects/98/57c9b3b0448c92818efc5fda0f206b21914168": "ecbde07c564dabbec0f249821051b8af",
".git/objects/a2/bdf743457b99c978cd1e9897d42b55497461c2": "c0fe0f0531b172b745dce168ed1db1c3",
".git/objects/a2/c2c501d92cade4609fbff2a20d058036a0f07d": "5799cad65f96fe00122233a5625d5041",
".git/objects/a5/3efdf92300d180241adcd14d81d15a734c4e08": "cdce04155deb291d95d5b2a8a45a186e",
".git/objects/a7/7659b5713281cbee988518b04eb402487b09d6": "568a65993cb018b20d2b96858193a0ad",
".git/objects/af/bded83b4ae7ef0140f9fbdb2b3155d5489db23": "f35e6272e2351c7023ad660fd5c409b5",
".git/objects/b1/b6389910633fe6f37c855d93c4447a575c02a4": "f91155044c840a451813894e34063006",
".git/objects/b7/49bfef07473333cf1dd31e9eed89862a5d52aa": "36b4020dca303986cad10924774fb5dc",
".git/objects/b9/2a0d854da9a8f73216c4a0ef07a0f0a44e4373": "f62d1eb7f51165e2a6d2ef1921f976f3",
".git/objects/b9/d460ca75a29691981ee5154d5cd6b030ec35b3": "749b9a51e817f2097741d99745a13d7b",
".git/objects/ba/5317db6066f0f7cfe94eec93dc654820ce848c": "9b7629bf1180798cf66df4142eb19a4e",
".git/objects/bb/9a5a3ef1c21c3aafde75f5d1bd75a79e51f76e": "ba8052d353ff35617d6ad93afe49e36e",
".git/objects/bd/0c76bd88d60a3735c7d512390dbe93a8cc9c1e": "cfad1dca1a813928555e2cf24e5d3ead",
".git/objects/c9/07b70128e6018eba4e2cfca9ebecc171af1832": "25411225c96defb759a19090a5cf4aa9",
".git/objects/cd/a13a99c1f3422f0c25cce89a049c05927d3dc7": "1454d7c9212cbfcf64e7c14322d45360",
".git/objects/ce/b76d53f4e1e527c43728779c716288c26b9a9b": "13fef70e0f3f952db8193cd88bc6c131",
".git/objects/cf/f35e7da229e61a6a903521ff4b861d26f9a80c": "5963053b3fa645610f0d041b16a41066",
".git/objects/d0/db61027b942bc0837d2d70b8c8148888256de8": "a3ec162d42e8dbb4e97a383c499d6543",
".git/objects/d4/3532a2348cc9c26053ddb5802f0e5d4b8abc05": "3dad9b209346b1723bb2cc68e7e42a44",
".git/objects/d6/9c56691fbdb0b7efa65097c7cc1edac12a6d3e": "868ce37a3a78b0606713733248a2f579",
".git/objects/d6/9de8d64a3a819b1b6b629dfa90a283968ed399": "9bfee8a38d606acb0370821534923ed0",
".git/objects/d8/99a38adc42d2f145111a3152534448be7cc202": "09acbecc77c9623c30c87acd2708b3a8",
".git/objects/e1/76c34d725786f2a4e76ba663c785304e8cbb10": "4520da88a60e4260dae5b07fa06892e0",
".git/objects/e3/203d3457cc9add82ec8ab78d2fb14d2cf3f843": "170b36bd2af4efe9f7ff056f83ff814a",
".git/objects/e7/4940e24389c921433e28e5927562e67460bd5a": "caeda771011fc98b1bc4f220a169aedf",
".git/objects/e8/7b47a5504733896678fff912074470e233415f": "9cf2f6c3aa1436395bde8dabd969d9c1",
".git/objects/eb/9b4d76e525556d5d89141648c724331630325d": "37c0954235cbe27c4d93e74fe9a578ef",
".git/objects/ec/61fa9445769cd6098e448f011c02b6fb4a7ce9": "e67210e49b86ae4aa3ab9e47a937af61",
".git/objects/f2/04823a42f2d890f945f70d88b8e2d921c6ae26": "6b47f314ffc35cf6a1ced3208ecc857d",
".git/objects/f5/010cda95492006dae3638dfb01a8d0822a1e6a": "04eb9fcdf209b67f396e5ab84cb956e2",
".git/refs/heads/master": "5d9baf72f86bf92b17d67beb27ea3539",
".git/refs/remotes/origin/gh-pages": "5d9baf72f86bf92b17d67beb27ea3539",
"assets/AssetManifest.bin": "693635b5258fe5f1cda720cf224f158c",
"assets/AssetManifest.bin.json": "69a99f98c8b1fb8111c5fb961769fcd8",
"assets/AssetManifest.json": "2efbb41d7877d10aac9d091f58ccd7b9",
"assets/FontManifest.json": "dc3d03800ccca4601324923c0b1d6d57",
"assets/NOTICES": "9a5e8e1b274372562056d2caa737ccf5",
"assets/fonts/MaterialIcons-Regular.otf": "edeb8529ff8108ee60d06b50f02ef3ae",
"assets/packages/cupertino_icons/assets/CupertinoIcons.ttf": "e986ebe42ef785b27164c36a9abc7818",
"assets/shaders/ink_sparkle.frag": "ecc85a2e95f5e9f53123dcaf8cb9b6ce",
"canvaskit/canvaskit.js": "5fda3f1af7d6433d53b24083e2219fa0",
"canvaskit/canvaskit.js.symbols": "48c83a2ce573d9692e8d970e288d75f7",
"canvaskit/canvaskit.wasm": "1f237a213d7370cf95f443d896176460",
"canvaskit/chromium/canvaskit.js": "87325e67bf77a9b483250e1fb1b54677",
"canvaskit/chromium/canvaskit.js.symbols": "a012ed99ccba193cf96bb2643003f6fc",
"canvaskit/chromium/canvaskit.wasm": "b1ac05b29c127d86df4bcfbf50dd902a",
"canvaskit/skwasm.js": "9fa2ffe90a40d062dd2343c7b84caf01",
"canvaskit/skwasm.js.symbols": "262f4827a1317abb59d71d6c587a93e2",
"canvaskit/skwasm.wasm": "9f0c0c02b82a910d12ce0543ec130e60",
"canvaskit/skwasm.worker.js": "bfb704a6c714a75da9ef320991e88b03",
"favicon.png": "5dcef449791fa27946b3d35ad8803796",
"flutter.js": "f31737fb005cd3a3c6bd9355efd33061",
"flutter_bootstrap.js": "0d42fc3ea445db32963145b4143946f0",
"icons/Icon-192.png": "ac9a721a12bbc803b44f645561ecb1e1",
"icons/Icon-512.png": "96e752610906ba2a93c65f8abe1645f1",
"icons/Icon-maskable-192.png": "c457ef57daa1d16f64b27b786ec2ea3c",
"icons/Icon-maskable-512.png": "301a7604d45b3e739efc881eb04896ea",
"index.html": "c150af08f86192785bf8bfda49be31c9",
"/": "c150af08f86192785bf8bfda49be31c9",
"main.dart.js": "ba9eb2165d100ec297c3e693b8c3c8aa",
"manifest.json": "686958ef200ff189d7ccbf2f48910467",
"version.json": "cd39222ab6489b1723577c9d720fc5ea"};
// The application shell files that are downloaded before a service worker can
// start.
const CORE = ["main.dart.js",
"index.html",
"flutter_bootstrap.js",
"assets/AssetManifest.bin.json",
"assets/FontManifest.json"];

// During install, the TEMP cache is populated with the application shell files.
self.addEventListener("install", (event) => {
  self.skipWaiting();
  return event.waitUntil(
    caches.open(TEMP).then((cache) => {
      return cache.addAll(
        CORE.map((value) => new Request(value, {'cache': 'reload'})));
    })
  );
});
// During activate, the cache is populated with the temp files downloaded in
// install. If this service worker is upgrading from one with a saved
// MANIFEST, then use this to retain unchanged resource files.
self.addEventListener("activate", function(event) {
  return event.waitUntil(async function() {
    try {
      var contentCache = await caches.open(CACHE_NAME);
      var tempCache = await caches.open(TEMP);
      var manifestCache = await caches.open(MANIFEST);
      var manifest = await manifestCache.match('manifest');
      // When there is no prior manifest, clear the entire cache.
      if (!manifest) {
        await caches.delete(CACHE_NAME);
        contentCache = await caches.open(CACHE_NAME);
        for (var request of await tempCache.keys()) {
          var response = await tempCache.match(request);
          await contentCache.put(request, response);
        }
        await caches.delete(TEMP);
        // Save the manifest to make future upgrades efficient.
        await manifestCache.put('manifest', new Response(JSON.stringify(RESOURCES)));
        // Claim client to enable caching on first launch
        self.clients.claim();
        return;
      }
      var oldManifest = await manifest.json();
      var origin = self.location.origin;
      for (var request of await contentCache.keys()) {
        var key = request.url.substring(origin.length + 1);
        if (key == "") {
          key = "/";
        }
        // If a resource from the old manifest is not in the new cache, or if
        // the MD5 sum has changed, delete it. Otherwise the resource is left
        // in the cache and can be reused by the new service worker.
        if (!RESOURCES[key] || RESOURCES[key] != oldManifest[key]) {
          await contentCache.delete(request);
        }
      }
      // Populate the cache with the app shell TEMP files, potentially overwriting
      // cache files preserved above.
      for (var request of await tempCache.keys()) {
        var response = await tempCache.match(request);
        await contentCache.put(request, response);
      }
      await caches.delete(TEMP);
      // Save the manifest to make future upgrades efficient.
      await manifestCache.put('manifest', new Response(JSON.stringify(RESOURCES)));
      // Claim client to enable caching on first launch
      self.clients.claim();
      return;
    } catch (err) {
      // On an unhandled exception the state of the cache cannot be guaranteed.
      console.error('Failed to upgrade service worker: ' + err);
      await caches.delete(CACHE_NAME);
      await caches.delete(TEMP);
      await caches.delete(MANIFEST);
    }
  }());
});
// The fetch handler redirects requests for RESOURCE files to the service
// worker cache.
self.addEventListener("fetch", (event) => {
  if (event.request.method !== 'GET') {
    return;
  }
  var origin = self.location.origin;
  var key = event.request.url.substring(origin.length + 1);
  // Redirect URLs to the index.html
  if (key.indexOf('?v=') != -1) {
    key = key.split('?v=')[0];
  }
  if (event.request.url == origin || event.request.url.startsWith(origin + '/#') || key == '') {
    key = '/';
  }
  // If the URL is not the RESOURCE list then return to signal that the
  // browser should take over.
  if (!RESOURCES[key]) {
    return;
  }
  // If the URL is the index.html, perform an online-first request.
  if (key == '/') {
    return onlineFirst(event);
  }
  event.respondWith(caches.open(CACHE_NAME)
    .then((cache) =>  {
      return cache.match(event.request).then((response) => {
        // Either respond with the cached resource, or perform a fetch and
        // lazily populate the cache only if the resource was successfully fetched.
        return response || fetch(event.request).then((response) => {
          if (response && Boolean(response.ok)) {
            cache.put(event.request, response.clone());
          }
          return response;
        });
      })
    })
  );
});
self.addEventListener('message', (event) => {
  // SkipWaiting can be used to immediately activate a waiting service worker.
  // This will also require a page refresh triggered by the main worker.
  if (event.data === 'skipWaiting') {
    self.skipWaiting();
    return;
  }
  if (event.data === 'downloadOffline') {
    downloadOffline();
    return;
  }
});
// Download offline will check the RESOURCES for all files not in the cache
// and populate them.
async function downloadOffline() {
  var resources = [];
  var contentCache = await caches.open(CACHE_NAME);
  var currentContent = {};
  for (var request of await contentCache.keys()) {
    var key = request.url.substring(origin.length + 1);
    if (key == "") {
      key = "/";
    }
    currentContent[key] = true;
  }
  for (var resourceKey of Object.keys(RESOURCES)) {
    if (!currentContent[resourceKey]) {
      resources.push(resourceKey);
    }
  }
  return contentCache.addAll(resources);
}
// Attempt to download the resource online before falling back to
// the offline cache.
function onlineFirst(event) {
  return event.respondWith(
    fetch(event.request).then((response) => {
      return caches.open(CACHE_NAME).then((cache) => {
        cache.put(event.request, response.clone());
        return response;
      });
    }).catch((error) => {
      return caches.open(CACHE_NAME).then((cache) => {
        return cache.match(event.request).then((response) => {
          if (response != null) {
            return response;
          }
          throw error;
        });
      });
    })
  );
}
